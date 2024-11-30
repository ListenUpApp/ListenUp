package scanner

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"time"

	logging "github.com/ListenUpApp/ListenUp/internal/logger"
	"github.com/ListenUpApp/ListenUp/internal/models"
	"github.com/ListenUpApp/ListenUp/internal/service"
	"github.com/fsnotify/fsnotify"
)

type Scanner struct {
	watcher        *fsnotify.Watcher
	watchedDirs    map[string]bool
	folderMap      map[string]*models.Folder
	mutex          sync.RWMutex
	logger         *logging.AppLogger
	contentService *service.ContentService
	mediaService   *service.MediaService
	ctx            context.Context
	cancel         context.CancelFunc
	debounceTimer  map[string]*time.Timer
	parser         *DirectoryParser

	// Supported audio extensions
	audioExts map[string]bool
}

type Config struct {
	Logger         *logging.AppLogger
	ContentService *service.ContentService
	MediaService   *service.MediaService
	ParseSubtitles bool
}

func New(cfg Config) (*Scanner, error) {
	if cfg.Logger == nil {
		return nil, fmt.Errorf("logger is required")
	}
	if cfg.ContentService == nil {
		return nil, fmt.Errorf("content service is required")
	}
	if cfg.MediaService == nil {
		return nil, fmt.Errorf("media service is required")
	}

	watcher, err := fsnotify.NewWatcher()
	if err != nil {
		return nil, fmt.Errorf("failed to create watcher: %w", err)
	}

	ctx, cancel := context.WithCancel(context.Background())

	return &Scanner{
		watcher:        watcher,
		watchedDirs:    make(map[string]bool),
		folderMap:      make(map[string]*models.Folder),
		logger:         cfg.Logger,
		contentService: cfg.ContentService,
		mediaService:   cfg.MediaService,
		ctx:            ctx,
		cancel:         cancel,
		debounceTimer:  make(map[string]*time.Timer),
		parser:         NewDirectoryParser(cfg.ParseSubtitles),
		audioExts: map[string]bool{
			".mp3":  true,
			".m4a":  true,
			".m4b":  true,
			".flac": true,
			".ogg":  true,
		},
	}, nil
}

func (s *Scanner) InitializeFromDB(ctx context.Context) error {
	// Get all folders from the database
	folders, err := s.mediaService.GetAllFolders(ctx)
	if err != nil {
		return fmt.Errorf("failed to get folders: %w", err)
	}

	s.mutex.Lock()
	defer s.mutex.Unlock()

	// Reset maps to ensure clean state
	s.watchedDirs = make(map[string]bool)
	s.folderMap = make(map[string]*models.Folder)

	for _, folder := range folders {
		s.logger.Debug("Initializing folder from database",
			"folder_id", folder.ID,
			"path", folder.Path)

		// Store folder in our map
		s.folderMap[folder.Path] = folder

		// Just set up the watchers initially
		err := filepath.Walk(folder.Path, func(path string, info os.FileInfo, err error) error {
			if err != nil {
				s.logger.Error("Error accessing path during initialization",
					"path", path,
					"error", err)
				return nil // Continue walking despite errors
			}

			if info.IsDir() {
				if err := s.watcher.Add(path); err != nil {
					s.logger.Error("Failed to watch directory during initialization",
						"path", path,
						"error", err)
					return nil
				}
				s.watchedDirs[path] = true
				s.logger.Debug("Added watcher to directory", "path", path)
			}

			return nil
		})

		if err != nil {
			s.logger.Error("Error walking folder path",
				"folder_id", folder.ID,
				"path", folder.Path,
				"error", err)
			continue
		}
	}

	s.logger.Info("Scanner initialized",
		"folder_count", len(s.folderMap),
		"watched_directories", len(s.watchedDirs))

	return nil
}

// Start begins watching for file system events
func (s *Scanner) Start() error {
	// Start the watcher
	go s.watchForEvents()

	// Process existing files in the background
	//go s.processExistingFiles()

	return nil
}
func (s *Scanner) processExistingFiles() {
	s.mutex.RLock()
	folders := make([]*models.Folder, 0, len(s.folderMap))
	for _, folder := range s.folderMap {
		folders = append(folders, folder)
	}
	s.mutex.RUnlock()

	for _, folder := range folders {
		s.logger.Debug("Processing existing files in folder",
			"folder_id", folder.ID,
			"path", folder.Path)

		err := filepath.Walk(folder.Path, func(path string, info os.FileInfo, err error) error {
			if err != nil {
				s.logger.Error("Error accessing path",
					"path", path,
					"error", err)
				return nil
			}

			if !info.IsDir() && s.isAudioFile(path) {
				s.processFile(path)
			}

			return nil
		})

		if err != nil {
			s.logger.Error("Error walking folder for existing files",
				"folder_id", folder.ID,
				"path", folder.Path,
				"error", err)
		}
	}

	s.logger.Info("Finished processing existing files")
}

func (s *Scanner) watchForEvents() {
	for {
		select {
		case event, ok := <-s.watcher.Events:
			if !ok {
				return
			}

			s.logger.Debug("Received filesystem event",
				"path", event.Name,
				"operation", event.Op.String(),
				"raw_op", int(event.Op))

			if event.Op&fsnotify.Write == fsnotify.Write {
				s.logger.Debug("Processing Write event",
					"path", event.Name,
					"is_audio", s.isAudioFile(event.Name))

				if s.isAudioFile(event.Name) {
					s.debounceFileProcess(event.Name)
				}
			} else if event.Op&fsnotify.Create == fsnotify.Create {
				info, err := os.Stat(event.Name)
				if err != nil {
					s.logger.Error("Failed to stat new path",
						"path", event.Name,
						"error", err)
					continue
				}

				s.logger.Debug("Processing Create event",
					"path", event.Name,
					"is_dir", info.IsDir(),
					"is_audio", s.isAudioFile(event.Name))

				if info.IsDir() {
					s.addDirectoryWatcher(event.Name)
				} else if s.isAudioFile(event.Name) {
					s.debounceFileProcess(event.Name)
				}
			}

		case err, ok := <-s.watcher.Errors:
			if !ok {
				return
			}
			s.logger.Error("Watcher error", "error", err)

		case <-s.ctx.Done():
			return
		}
	}
}

func (s *Scanner) debounceFileProcess(path string) {
	s.mutex.Lock()
	defer s.mutex.Unlock()

	// Cancel any existing timer
	if timer, exists := s.debounceTimer[path]; exists {
		timer.Stop()
	}

	// Set new timer
	s.debounceTimer[path] = time.AfterFunc(2*time.Second, func() {
		s.processFile(path)
	})
}

func (s *Scanner) addDirectoryWatcher(path string) {
	s.mutex.Lock()
	defer s.mutex.Unlock()

	s.logger.Debug("Beginning directory watcher addition",
		"path", path,
		"already_watched", s.watchedDirs[path])

	// Walk the directory tree and add watchers to all subdirectories
	err := filepath.Walk(path, func(subPath string, info os.FileInfo, err error) error {
		if err != nil {
			s.logger.Error("Error accessing path during walk",
				"path", subPath,
				"error", err)
			return nil // Continue walking despite errors
		}

		if info.IsDir() {
			// Skip if already watching
			if s.watchedDirs[subPath] {
				s.logger.Debug("Skipping already watched directory", "path", subPath)
				return nil
			}

			if err := s.watcher.Add(subPath); err != nil {
				s.logger.Error("Failed to watch directory",
					"path", subPath,
					"error", err)
				return nil // Continue walking despite errors
			}
			s.watchedDirs[subPath] = true
			s.logger.Debug("Successfully added watcher to directory", "path", subPath)
		}

		return nil
	})

	if err != nil {
		s.logger.Error("Failed to walk directory tree",
			"path", path,
			"error", err)
	}
}

// ScanFolder scans a folder and its subdirectories for audiobooks
func (s *Scanner) ScanFolder(ctx context.Context, folder *models.Folder) error {
	s.mutex.Lock()
	s.folderMap[folder.Path] = folder
	s.watchedDirs[folder.Path] = true
	s.mutex.Unlock()

	// Add watchers for all directories
	s.addDirectoryWatcher(folder.Path)

	// Process existing audio files
	return filepath.Walk(folder.Path, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			s.logger.Error("Error accessing path",
				"path", path,
				"error", err)
			return nil // Continue walking despite errors
		}

		if !info.IsDir() && s.isAudioFile(path) {
			s.processFile(path)
		}

		return nil
	})
}

// Stop terminates the scanner and cleans up resources
func (s *Scanner) Stop() error {
	s.cancel()

	s.mutex.Lock()
	defer s.mutex.Unlock()

	for _, timer := range s.debounceTimer {
		timer.Stop()
	}

	s.debounceTimer = make(map[string]*time.Timer)
	s.watchedDirs = make(map[string]bool)
	s.folderMap = make(map[string]*models.Folder)

	return s.watcher.Close()
}
