package service

import (
	"context"
	"fmt"

	appErr "github.com/ListenUpApp/ListenUp/internal/error"
	logging "github.com/ListenUpApp/ListenUp/internal/logger"
	"github.com/ListenUpApp/ListenUp/internal/models"
	"github.com/ListenUpApp/ListenUp/internal/repository/content"
	"github.com/ListenUpApp/ListenUp/internal/repository/media"
	"github.com/go-playground/validator/v10"
)

type ContentService struct {
	contentRepo *content.Repository
	mediaRepo   *media.Repository
	logger      *logging.AppLogger
	validator   *validator.Validate
}

func NewContentService(cfg ServiceConfig) (*ContentService, error) {
	if cfg.MediaRepo == nil {
		return nil, fmt.Errorf("media repository is required")
	}
	if cfg.ContentRepo == nil {
		return nil, fmt.Errorf("content repository is required")
	}
	if cfg.Logger == nil {
		return nil, fmt.Errorf("logger is required")
	}

	return &ContentService{
		contentRepo: cfg.ContentRepo,
		mediaRepo:   cfg.MediaRepo,
		logger:      cfg.Logger,
		validator:   cfg.Validator,
	}, nil
}

func (s *ContentService) CreateBook(ctx context.Context, folderID string, params models.CreateAudiobookRequest) (*models.Audiobook, error) {
	folder, err := s.mediaRepo.Folders.GetByID(ctx, folderID)
	if err != nil {
		return nil, appErr.HandleRepositoryError(err, "GetFolder", map[string]interface{}{
			"folder_id": folderID,
		})
	}

	libraries, err := s.mediaRepo.Folders.GetLibrariesForFolder(ctx, folderID)
	if err != nil {
		return nil, appErr.HandleRepositoryError(err, "GetLibrariesForFolder", map[string]interface{}{
			"folder_id": folderID,
		})
	}

	if len(libraries) == 0 {
		return nil, appErr.NewServiceError(appErr.ErrNotFound, "folder is not associated with any libraries", nil).
			WithOperation("CreateBook").
			WithData(map[string]interface{}{"folder_id": folderID})
	}

	dbBook, err := s.contentRepo.Books.Create(ctx, params, folder, libraries[0])
	if err != nil {
		return nil, err
	}

	book := models.Audiobook{
		ID:            dbBook.ID,
		Title:         dbBook.Title,
		Duration:      int64(dbBook.Duration),
		Size:          dbBook.Size,
		Subtitle:      dbBook.Subtitle,
		Description:   dbBook.Description,
		Isbn:          dbBook.Isbn,
		Asin:          dbBook.Asin,
		Language:      dbBook.Language,
		Explicit:      dbBook.Explicit,
		Publisher:     dbBook.Publisher,
		PublishedDate: dbBook.PublishedDate,
		Genres:        dbBook.Genres,
	}

	if dbBook.Edges.Authors != nil {
		for _, author := range dbBook.Edges.Authors {
			book.Authors = append(book.Authors, models.Author{
				ID:          author.ID,
				Name:        author.Name,
				Description: author.Description,
			})
		}
	}

	if dbBook.Edges.Narrators != nil {
		for _, narrator := range dbBook.Edges.Narrators {
			book.Narrators = append(book.Narrators, models.Narrator{
				ID:          narrator.ID,
				Name:        narrator.Name,
				Description: narrator.Description,
			})
		}
	}

	if dbBook.Edges.Cover != nil {
		book.Cover = models.Cover{
			Path:   dbBook.Edges.Cover.Path,
			Format: dbBook.Edges.Cover.Format,
			Size:   dbBook.Edges.Cover.Size,
		}
	}

	return &book, nil
}
