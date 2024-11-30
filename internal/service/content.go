package service

import (
	"context"
	"fmt"
	"github.com/ListenUpApp/ListenUp/internal/config"
	appErr "github.com/ListenUpApp/ListenUp/internal/error"
	logging "github.com/ListenUpApp/ListenUp/internal/logger"
	"github.com/ListenUpApp/ListenUp/internal/models"
	"github.com/ListenUpApp/ListenUp/internal/repository/content"
	"github.com/ListenUpApp/ListenUp/internal/repository/media"
	"github.com/ListenUpApp/ListenUp/internal/util"
	"github.com/go-playground/validator/v10"
	"os"
	"path/filepath"
)

type ContentService struct {
	contentRepo  *content.Repository
	mediaRepo    *media.Repository
	imageService *ImageService
	config       config.MetadataConfig
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
		contentRepo: cfg.ContentRepo,
		mediaRepo:   cfg.MediaRepo,
		config:      cfg.config.Metadata,
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

	bookID := util.NewID()
	// Process CoverData if available
	if params.CoverData != nil {
		coverRequest, err := s.processBookCover(params.CoverData.Data, bookID)
		if err != nil {
			s.logger.Error("Failed to process cover image", "error", err)
			// Continue without cover
		} else {
			params.Cover = *coverRequest
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

func (s *ContentService) processBookCover(coverData []byte, bookID string) (*models.CreateCoverRequest, error) {
	s.logger.Debug("Processing cover image",
		"data_size", len(coverData),
		"book_id", bookID)

	// Initialize image processor with config settings
	processor := util.NewImageProcessor(
		s.config.WebPQuality,
		s.config.NoiseReduction,
		s.config.Sharpening,
	)

	// Process original image with MIME type
	processed, err := processor.ProcessImage(coverData, 1200, 1200, "image/jpeg") // Assuming JPEG, adjust if needed
	if err != nil {
		return nil, fmt.Errorf("failed to process cover: %w", err)
	}

	// Get source image for versions
	sourceImage, _, err := processor.ProcessRawImage(coverData, "image/jpeg")
	if err != nil {
		return nil, fmt.Errorf("failed to process source image: %w", err)
	}

	// Define cover image sizes
	sizes := []util.ImageSize{
		{Width: 150, Height: 150, Scale: 1.0, Suffix: "thumbnail"},
		{Width: 150, Height: 150, Scale: 2.0, Suffix: "thumbnail@2x"},
		{Width: 300, Height: 300, Scale: 1.0, Suffix: "small"},
		{Width: 300, Height: 300, Scale: 2.0, Suffix: "small@2x"},
		{Width: 600, Height: 600, Scale: 1.0, Suffix: "medium"},
		{Width: 600, Height: 600, Scale: 2.0, Suffix: "medium@2x"},
		{Width: 1200, Height: 1200, Scale: 1.0, Suffix: "large"},
		{Width: 1200, Height: 1200, Scale: 2.0, Suffix: "large@2x"},
	}

	// Create all versions
	versions, err := processor.CreateVersions(sourceImage, sizes)
	if err != nil {
		return nil, fmt.Errorf("failed to create cover versions: %w", err)
	}

	// Save files to disk
	coverPath := filepath.Join("covers", bookID)
	absolutePath := filepath.Join(s.config.BasePath, coverPath)

	if err := os.MkdirAll(absolutePath, 0755); err != nil {
		return nil, fmt.Errorf("failed to create cover directory: %w", err)
	}

	// Save original
	originalPath := filepath.Join(coverPath, "original.webp")
	if err := os.WriteFile(
		filepath.Join(s.config.BasePath, originalPath),
		processed.Data,
		0644,
	); err != nil {
		return nil, fmt.Errorf("failed to save original cover: %w", err)
	}

	// Prepare cover request
	coverRequest := &models.CreateCoverRequest{
		Path:   "/media/" + originalPath,
		Format: processed.Format,
		Size:   processed.Size,
	}

	// Save and add versions
	for i, version := range versions {
		versionPath := filepath.Join(coverPath, fmt.Sprintf("%s.webp", sizes[i].Suffix))

		if err := os.WriteFile(
			filepath.Join(s.config.BasePath, versionPath),
			version.Data,
			0644,
		); err != nil {
			return nil, fmt.Errorf("failed to save cover version %s: %w", sizes[i].Suffix, err)
		}

		coverRequest.Versions = append(coverRequest.Versions, models.CreateCoverVersionRequest{
			Path:   "/media/" + versionPath,
			Format: "image/webp",
			Size:   version.Size,
			Suffix: sizes[i].Suffix,
		})
	}

	return coverRequest, nil
}
