package scanner

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	logging "github.com/ListenUpApp/ListenUp/internal/logger"
	"github.com/ListenUpApp/ListenUp/internal/models"
	"github.com/ListenUpApp/ListenUp/internal/service"
	"github.com/ListenUpApp/ListenUp/pkg/taggart"
	"github.com/fsnotify/fsnotify"
)

type Scanner struct {
	watcher         *fsnotify.Watcher
	watchedDirs     map[string]bool
	folderMap       map[string]*models.Folder
	mutex           sync.RWMutex
	audioExts       map[string]bool
	logger          *logging.AppLogger
	contentService  *service.ContentService
	mediaService    *service.MediaService
	ctx             context.Context
	cancel          context.CancelFunc
	debounceTimer   map[string]*time.Timer
	directoryParser *DirectoryParser
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
		return nil, fmt.Errorf("failed to create watcher: %v", err)
	}

	ctx, cancel := context.WithCancel(context.Background())

	return &Scanner{
		watcher:     watcher,
		watchedDirs: make(map[string]bool),
		folderMap:   make(map[string]*models.Folder),
		audioExts: map[string]bool{
			".mp3":  true,
			".m4a":  true,
			".m4b":  true,
			".flac": true,
			".ogg":  true,
		},
		mutex:           sync.RWMutex{},
		logger:          cfg.Logger,
		contentService:  cfg.ContentService,
		mediaService:    cfg.MediaService,
		ctx:             ctx,
		cancel:          cancel,
		debounceTimer:   make(map[string]*time.Timer),
		directoryParser: NewDirectoryParser(cfg.ParseSubtitles),
	}, nil
}

func (s *Scanner) InitializeFromDB(ctx context.Context) error {
	folders, err := s.mediaService.GetAllFolders(ctx)
	if err != nil {
		return fmt.Errorf("failed to get folders: %w", err)
	}

	s.mutex.Lock()
	defer s.mutex.Unlock()

	s.folderMap = make(map[string]*models.Folder)
	for _, folder := range folders {
		s.folderMap[folder.Path] = folder
		if err := s.watcher.Add(folder.Path); err != nil {
			s.logger.Error("Failed to watch folder", "path", folder.Path, "error", err)
			continue
		}
		s.watchedDirs[folder.Path] = true
	}

	return nil
}

func (s *Scanner) AddFolder(folder *models.Folder) error {
	s.mutex.Lock()
	defer s.mutex.Unlock()

	if _, exists := s.watchedDirs[folder.Path]; exists {
		return nil
	}

	if err := s.watcher.Add(folder.Path); err != nil {
		return fmt.Errorf("failed to watch folder %s: %w", folder.Path, err)
	}

	s.folderMap[folder.Path] = folder
	s.watchedDirs[folder.Path] = true
	return nil
}

func (s *Scanner) ScanFolder(ctx context.Context, folder *models.Folder) error {
	s.mutex.Lock()
	s.folderMap[folder.Path] = folder
	s.watchedDirs[folder.Path] = true
	s.mutex.Unlock()

	if err := s.watcher.Add(folder.Path); err != nil {
		s.mutex.Lock()
		delete(s.folderMap, folder.Path)
		delete(s.watchedDirs, folder.Path)
		s.mutex.Unlock()
		return fmt.Errorf("failed to watch folder: %w", err)
	}

	return filepath.Walk(folder.Path, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		if !info.IsDir() && s.isAudioFile(path) {
			s.processFile(path)
		}
		return nil
	})
}

func (s *Scanner) processFile(path string) {
	s.mutex.Lock()
	delete(s.debounceTimer, path)
	s.mutex.Unlock()

	dirPath := filepath.Dir(path)
	s.mutex.RLock()
	folder, exists := s.folderMap[dirPath]
	s.mutex.RUnlock()

	if !exists {
		return
	}

	metadata, err := s.directoryParser.ParseBookPath(path)
	if err != nil {
		return
	}

	file, err := os.Open(path)
	if err != nil {
		return
	}
	defer file.Close()

	fileInfo, err := file.Stat()
	if err != nil {
		return
	}

	audioMeta, err := taggart.ReadFrom(file)
	if err != nil {
		return
	}

	mergedMeta := mergeMetadata(metadata, audioMeta)

	var authors []models.CreateAuthorRequest
	if len(mergedMeta.Authors) > 0 {
		for _, author := range mergedMeta.Authors {
			authors = append(authors, models.CreateAuthorRequest{
				Name:        cleanString(fmt.Sprintf("%s %s", author.FirstName, author.LastName)),
				Description: "",
			})
		}
	} else if audioMeta.Artist() != "" {
		for _, name := range splitAuthors(audioMeta.Artist()) {
			authors = append(authors, models.CreateAuthorRequest{
				Name:        cleanString(name),
				Description: "",
			})
		}
	}

	var narrators []models.CreateNarratorRequest
	if mergedMeta.Narrator != "" {
		for _, name := range splitNarrators(mergedMeta.Narrator) {
			narrators = append(narrators, models.CreateNarratorRequest{
				Name:        cleanString(name),
				Description: "",
			})
		}
	} else if len(audioMeta.Narrators()) > 0 {
		for _, name := range audioMeta.Narrators() {
			narrators = append(narrators, models.CreateNarratorRequest{
				Name:        cleanString(name),
				Description: "",
			})
		}
	}

	var chapters []models.CreateChapterRequest
	for _, chapter := range audioMeta.Chapters() {
		chapters = append(chapters, models.CreateChapterRequest{
			Title: cleanString(chapter.Title),
			Start: float64(chapter.Start),
			End:   float64(chapter.End),
		})
	}

	var coverRequest models.CreateCoverRequest
	if picture := audioMeta.Picture(); picture != nil {
		tempDir := filepath.Join(os.TempDir(), "listenup_covers")
		if err := os.MkdirAll(tempDir, 0755); err != nil {
			return
		}

		coverPath := filepath.Join(tempDir,
			fmt.Sprintf("%s_%s.%s",
				strings.TrimSuffix(filepath.Base(path), filepath.Ext(path)),
				time.Now().Format("20060102150405"),
				picture.Ext))

		if err := os.WriteFile(coverPath, picture.Data, 0644); err != nil {
			return
		}

		if info, err := os.Stat(coverPath); err == nil {
			coverRequest = models.CreateCoverRequest{
				Path:   coverPath,
				Format: picture.MIMEType,
				Size:   info.Size(),
			}
		}
	}

	createBookData := models.CreateAudiobookRequest{
		Title:         cleanString(audioMeta.Title()),
		Subtitle:      cleanString(mergedMeta.Subtitle),
		Duration:      int64(audioMeta.Duration()),
		Size:          fileInfo.Size(),
		Description:   cleanString(audioMeta.Comment()),
		Isbn:          cleanString(audioMeta.ISBN()),
		Asin:          cleanString(audioMeta.ASIN()),
		Language:      cleanString(audioMeta.Language()),
		Publisher:     cleanString(audioMeta.Publisher()),
		PublishedDate: time.Date(audioMeta.Year(), 1, 1, 0, 0, 0, 0, time.UTC),
		Genres:        []string{cleanString(audioMeta.Genre())},
		Authors:       authors,
		Narrators:     narrators,
		Chapter:       chapters,
		Cover:         coverRequest,
	}

	if _, err := s.contentService.CreateBook(s.ctx, folder.ID, createBookData); err != nil {
		s.logger.Error("Failed to create book", "path", path, "error", err)
	}
}

func (s *Scanner) isAudioFile(path string) bool {
	return s.audioExts[strings.ToLower(filepath.Ext(path))]
}

func cleanString(s string) string {
	s = strings.Map(func(r rune) rune {
		if r < 32 || r == 127 {
			return -1
		}
		return r
	}, s)
	return strings.TrimSpace(s)
}

func splitAuthors(authorStr string) []string {
	delimiters := []string{";", "&", "and", ","}
	authors := []string{authorStr}
	for _, delimiter := range delimiters {
		var newAuthors []string
		for _, author := range authors {
			split := strings.Split(author, delimiter)
			for _, s := range split {
				if trimmed := strings.TrimSpace(s); trimmed != "" {
					newAuthors = append(newAuthors, trimmed)
				}
			}
		}
		authors = newAuthors
	}
	return authors
}

func splitNarrators(narratorStr string) []string {
	return splitAuthors(narratorStr) // Same logic as authors
}

func mergeMetadata(dirMeta BookMetadata, audioMeta taggart.Metadata) BookMetadata {
	result := dirMeta
	if result.Title == "" {
		result.Title = audioMeta.Title()
	}
	if len(result.Authors) == 0 && audioMeta.Artist() != "" {
		parts := strings.Split(audioMeta.Artist(), " ")
		if len(parts) > 1 {
			result.Authors = []Author{{
				FirstName: strings.Join(parts[:len(parts)-1], " "),
				LastName:  parts[len(parts)-1],
			}}
		}
	}
	return result
}

func (s *Scanner) Start() error {
	go func() {
		for {
			select {
			case event, ok := <-s.watcher.Events:
				if !ok {
					return
				}
				if event.Op&fsnotify.Write == fsnotify.Write && s.isAudioFile(event.Name) {
					s.mutex.Lock()
					if timer, exists := s.debounceTimer[event.Name]; exists {
						timer.Stop()
					}
					s.debounceTimer[event.Name] = time.AfterFunc(2*time.Second, func() {
						s.processFile(event.Name)
					})
					s.mutex.Unlock()
				}
			case err, ok := <-s.watcher.Errors:
				if !ok {
					return
				}
				s.logger.Error("Scanner error", "error", err)
			case <-s.ctx.Done():
				return
			}
		}
	}()
	return nil
}

func (s *Scanner) Stop() error {
	s.cancel()

	s.mutex.Lock()
	for _, timer := range s.debounceTimer {
		if timer != nil {
			timer.Stop()
		}
	}
	s.debounceTimer = make(map[string]*time.Timer)
	s.watchedDirs = make(map[string]bool)
	s.folderMap = make(map[string]*models.Folder)
	s.mutex.Unlock()

	return s.watcher.Close()
}
