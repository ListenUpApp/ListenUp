package scanner

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"time"

	logging "github.com/ListenUpApp/ListenUp/internal/logger"
	"github.com/ListenUpApp/ListenUp/internal/service"
	"github.com/ListenUpApp/ListenUp/pkg/taggart"
	"github.com/fsnotify/fsnotify"
)

type Scanner struct {
	watcher       *fsnotify.Watcher
	watchedDirs   map[string]bool
	mutex         sync.RWMutex
	audioExts     map[string]bool
	logger        *logging.AppLogger
	mediaService  *service.MediaService
	ctx           context.Context
	cancel        context.CancelFunc
	debounceTimer map[string]*time.Timer
}

type Config struct {
	Logger       *logging.AppLogger
	MediaService *service.MediaService
}

func New(cfg Config) (*Scanner, error) {
	if cfg.Logger == nil {
		return nil, fmt.Errorf("logger is required")
	}
	if cfg.MediaService == nil {
		return nil, fmt.Errorf("media service is required")
	}

	watcher, err := fsnotify.NewWatcher()
	if err != nil {
		return nil, fmt.Errorf("failed to create watcher: %v", err)
	}

	ctx, cancel := context.WithCancel(context.Background())

	return &Scanner{
		watcher:     watcher,
		watchedDirs: make(map[string]bool),
		audioExts: map[string]bool{
			".mp3":  true,
			".m4a":  true,
			".m4b":  true,
			".wav":  true,
			".flac": true,
			".ogg":  true,
		},
		mutex:         sync.RWMutex{},
		logger:        cfg.Logger,
		mediaService:  cfg.MediaService,
		ctx:           ctx,
		cancel:        cancel,
		debounceTimer: make(map[string]*time.Timer),
	}, nil
}

func (s *Scanner) InitializeFromDB(ctx context.Context) error {
	s.mutex.Lock()
	defer s.mutex.Unlock()

	// Get all folders through the service layer
	folders, err := s.mediaService.GetAllFolders(ctx)
	if err != nil {
		return fmt.Errorf("failed to get folders: %v", err)
	}

	for _, folder := range folders {
		if err := s.addSingleDirectory(folder.Path); err != nil {
			s.logger.ErrorContext(ctx, "Failed to watch folder",
				"path", folder.Path,
				"error", err)
			continue
		}
	}

	return nil
}

func (s *Scanner) addSingleDirectory(path string) error {
	absPath, err := filepath.Abs(path)
	if err != nil {
		return fmt.Errorf("failed to get absolute path: %v", err)
	}

	// Validate through the service layer
	if err := s.mediaService.ValidateFolderPath(context.Background(), absPath); err != nil {
		return err
	}

	if err := s.watcher.Add(absPath); err != nil {
		return fmt.Errorf("failed to watch directory %s: %v", absPath, err)
	}

	s.watchedDirs[absPath] = true
	s.logger.Info("Added directory to watch list", "path", absPath)
	return nil
}

func (s *Scanner) AddDirectory(ctx context.Context, path string) error {
	s.mutex.Lock()
	defer s.mutex.Unlock()

	return s.addSingleDirectory(path)
}

func (s *Scanner) RemoveDirectory(ctx context.Context, path string) error {
	s.mutex.Lock()
	defer s.mutex.Unlock()

	absPath, err := filepath.Abs(path)
	if err != nil {
		return fmt.Errorf("failed to get absolute path: %v", err)
	}

	if err := s.watcher.Remove(absPath); err != nil {
		return fmt.Errorf("failed to remove watch on directory: %v", err)
	}

	delete(s.watchedDirs, absPath)
	s.logger.Info("Removed directory from watch list", "path", absPath)
	return nil
}

func (s *Scanner) Start() {
	go func() {
		for {
			select {
			case <-s.ctx.Done():
				return
			case event, ok := <-s.watcher.Events:
				if !ok {
					return
				}
				s.handleEvent(event)
			case err, ok := <-s.watcher.Errors:
				if !ok {
					return
				}
				s.logger.Error("Watcher error", "error", err)
			}
		}
	}()

	s.logger.Info("File scanner started")
}

func (s *Scanner) handleEvent(event fsnotify.Event) {
	if event.Op&fsnotify.Write == 0 {
		return
	}

	ext := filepath.Ext(event.Name)
	if !s.audioExts[ext] {
		return
	}

	s.mutex.Lock()
	defer s.mutex.Unlock()

	// Cancel existing timer for this file if it exists
	if timer, exists := s.debounceTimer[event.Name]; exists {
		timer.Stop()
	}

	// Create new timer for this file
	s.debounceTimer[event.Name] = time.AfterFunc(2*time.Second, func() {
		s.processFile(event.Name)
	})
}

func (s *Scanner) processFile(path string) {
	s.mutex.Lock()
	delete(s.debounceTimer, path)
	s.mutex.Unlock()

	// Check if file is accessible
	file, err := os.Open(path)
	if err != nil {
		s.logger.Error("Failed to access file",
			"path", path,
			"error", err)
		return
	}
	book, err := taggart.ReadFrom(file)
	if err != nil {
		s.logger.Error("Failed to parse file",
			"path", path,
			"error", err)
		return
	}
	file.Close()

	s.logger.Info("Parsed audio file", book.Title(), book.Artist())

	ChapterList := book.Chapters()
	if len(ChapterList) > 0 {
		s.logger.Info("Chapter list:",
			"total_chapters", len(ChapterList))

		for i, chapter := range ChapterList {
			s.logger.Info("Chapter details",
				"number", i+1,
				"title", chapter.Title,
				"start_time", formatDuration(chapter.Start),
				"end_time", formatDuration(chapter.End))
		}
	}
	if err := s.mediaService.HandleNewAudioFile(context.Background(), path); err != nil {
		s.logger.Error("Failed to process audio file",
			"path", path,
			"error", err)
		return
	}

	s.logger.Info("Successfully queued audio file for processing",
		"path", path)
}
func (s *Scanner) Stop() error {
	s.cancel()
	return s.watcher.Close()
}

type Chapter struct {
	id    uint8
	Start int64
	End   int64
	Title string
}

func formatDuration(seconds int64) string {
	h := seconds / 3600
	m := (seconds % 3600) / 60
	s := seconds % 60

	if h > 0 {
		return fmt.Sprintf("%02d:%02d:%02d", h, m, s)
	}
	return fmt.Sprintf("%02d:%02d", m, s)
}
