package scanner

import (
	"context"
	"fmt"
	"os"
	"strings"
	"sync"
	"time"

	logging "github.com/ListenUpApp/ListenUp/internal/logger"
	"github.com/ListenUpApp/ListenUp/internal/service"
	"github.com/ListenUpApp/ListenUp/pkg/taggart"
	"github.com/fsnotify/fsnotify"
)

type Scanner struct {
	watcher         *fsnotify.Watcher
	watchedDirs     map[string]bool
	mutex           sync.RWMutex
	audioExts       map[string]bool
	logger          *logging.AppLogger
	mediaService    *service.MediaService
	ctx             context.Context
	cancel          context.CancelFunc
	debounceTimer   map[string]*time.Timer
	directoryParser *DirectoryParser
}

type Config struct {
	Logger         *logging.AppLogger
	MediaService   *service.MediaService
	ParseSubtitles bool 
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
			".flac": true,
			".ogg":  true,
		},
		mutex:           sync.RWMutex{},
		logger:          cfg.Logger,
		mediaService:    cfg.MediaService,
		ctx:             ctx,
		cancel:          cancel,
		debounceTimer:   make(map[string]*time.Timer),
		directoryParser: NewDirectoryParser(cfg.ParseSubtitles), // Initialize the directory parser
	}, nil
}

func (s *Scanner) processFile(path string) {
	s.mutex.Lock()
	delete(s.debounceTimer, path)
	s.mutex.Unlock()

	// Parse directory structure first
	metadata, err := s.directoryParser.ParseBookPath(path)
	if err != nil {
		s.logger.Error("Failed to parse directory structure",
			"path", path,
			"error", err)
		return
	}

	// Check if file is accessible
	file, err := os.Open(path)
	if err != nil {
		s.logger.Error("Failed to access file",
			"path", path,
			"error", err)
		return
	}
	defer file.Close()

	// Read audio file metadata as backup
	audioMeta, err := taggart.ReadFrom(file)
	if err != nil {
		s.logger.Error("Failed to parse file",
			"path", path,
			"error", err)
		return
	}

	// Merge metadata, preferring directory structure over file tags
	mergedMeta := mergeMetadata(metadata, audioMeta)

	s.logger.Info("Parsed audio file",
		"title", mergedMeta.Title,
		"authors", formatAuthors(mergedMeta.Authors),
		"series", mergedMeta.Series,
		"series_index", mergedMeta.SeriesIndex,
		"publish_year", mergedMeta.PublishYear,
		"narrator", mergedMeta.Narrator)

	if err := s.mediaService.HandleNewAudioFile(context.Background(), path, mergedMeta); err != nil {
		s.logger.Error("Failed to process audio file",
			"path", path,
			"error", err)
		return
	}

	s.logger.Info("Successfully queued audio file for processing",
		"path", path)
}

func mergeMetadata(dirMeta BookMetadata, audioMeta *taggart.AudioBook) BookMetadata {
	// Start with directory metadata
	result := dirMeta

	// Only use audio metadata if directory metadata is missing
	if result.Title == "" {
		result.Title = audioMeta.Title()
	}
	if len(result.Authors) == 0 && audioMeta.Artist() != "" {
		// Simple split on last name for audio metadata
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

func formatAuthors(authors []Author) string {
	var names []string
	for _, author := range authors {
		names = append(names, fmt.Sprintf("%s %s", author.FirstName, author.LastName))
	}
	return strings.Join(names, ", ")
}
