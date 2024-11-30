package service

import (
	"context"
	"fmt"
	appErr "github.com/ListenUpApp/ListenUp/internal/error"
	logging "github.com/ListenUpApp/ListenUp/internal/logger"
	"github.com/ListenUpApp/ListenUp/internal/models"
	"github.com/ListenUpApp/ListenUp/internal/repository/content"
	"github.com/ListenUpApp/ListenUp/internal/repository/media"
	"github.com/ListenUpApp/ListenUp/internal/util"
	"github.com/go-playground/validator/v10"
)

type ContentService struct {
	contentRepo  *content.Repository
	mediaRepo    *media.Repository
	imageService *ImageService
	logger       *logging.AppLogger
	validator    *validator.Validate
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
		contentRepo:  cfg.ContentRepo,
		mediaRepo:    cfg.MediaRepo,
		imageService: cfg.ImageService,
		logger:       cfg.Logger,
		validator:    cfg.Validator,
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

	bookID := util.NewID()
	// Process CoverData if available
	if params.CoverData != nil {
		processed, err := s.imageService.ProcessCoverImage(params.CoverData, bookID)
		if err != nil {
			s.logger.Error("Failed to process cover image", "error", err)
			// Continue without cover
		} else if processed != nil {
			s.logger.Info("Cover image processed",
				"original_size", processed.Size,
				"version_count", len(processed.Versions))

			params.Cover = models.CreateCoverRequest{
				Path:   processed.OriginalPath,
				Format: processed.Format,
				Size:   processed.Size, // Log this value
			}

			s.logger.Info("Creating cover request",
				"path", params.Cover.Path,
				"format", params.Cover.Format,
				"size", params.Cover.Size)

			for _, version := range processed.Versions {
				s.logger.Debug("Adding cover version",
					"path", version.Path,
					"size", version.Size,
					"suffix", version.Suffix)

				params.Cover.Versions = append(params.Cover.Versions, models.CreateCoverVersionRequest{
					Path:   version.Path,
					Format: "image/webp",
					Size:   version.Size,
					Suffix: version.Suffix,
				})
			}
		}
	}

	dbBook, err := s.contentRepo.Books.Create(ctx, bookID, params, folder, libraries[0])
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
		book.Cover.Path = dbBook.Edges.Cover.Path
		book.Cover.Format = dbBook.Edges.Cover.Format
		book.Cover.Size = dbBook.Edges.Cover.Size

		if dbBook.Edges.Cover.Edges.Versions != nil {
			for _, version := range dbBook.Edges.Cover.Edges.Versions {
				book.Cover.Versions = append(book.Cover.Versions, models.CoverVersion{
					Path:   version.Path,
					Format: version.Format,
					Size:   version.Size,
					Suffix: version.Suffix,
				})
			}
		}
	}

	return &book, nil
}
