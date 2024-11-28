package service

import (
	"context"
	"fmt"

	appErr "github.com/ListenUpApp/ListenUp/internal/error"
	logging "github.com/ListenUpApp/ListenUp/internal/logger"
	"github.com/ListenUpApp/ListenUp/internal/models"
	"github.com/ListenUpApp/ListenUp/internal/repository"
	"github.com/ListenUpApp/ListenUp/internal/repository/media"
	"github.com/go-playground/validator/v10"
)

type MediaService struct {
	mediaRepo *media.Repository
	userRepo  *repository.UserRepository
	logger    *logging.AppLogger
	validator *validator.Validate
}

func NewMediaService(cfg ServiceConfig) (*MediaService, error) {
	if cfg.MediaRepo == nil {
		return nil, fmt.Errorf("media repository is required")
	}
	if cfg.Logger == nil {
		return nil, fmt.Errorf("logger is required")
	}

	return &MediaService{
		mediaRepo: cfg.MediaRepo,
		userRepo:  cfg.UserRepo,
		logger:    cfg.Logger,
		validator: cfg.Validator,
	}, nil
}

func (s *MediaService) GetFolderStructure(ctx context.Context, req models.GetFolderRequest) (*models.GetFolderResponse, error) {
	// Validate request
	if err := s.validator.Struct(req); err != nil {
		return nil, appErr.NewServiceError(appErr.ErrValidation, "invalid folder request", err).
			WithOperation("GetFolderStructure").
			WithData(map[string]interface{}{
				"path":  req.Path,
				"depth": req.Depth,
			})
	}

	folder, err := s.mediaRepo.Folders.GetOSFolderWithDepth(ctx, req.Path, req.Depth)
	if err != nil {
		return nil, appErr.HandleRepositoryError(err, "GetFolderStructure", map[string]interface{}{
			"path":  req.Path,
			"depth": req.Depth,
		})
	}

	return folder, nil
}

func (s *MediaService) GetAllFolders(ctx context.Context) ([]*models.Folder, error) {
	folders, err := s.mediaRepo.Folders.GetAll(ctx)
	if err != nil {
		return nil, appErr.HandleRepositoryError(err, "GetAllFolders", nil)
	}

	var result []*models.Folder
	for _, f := range folders {
		result = append(result, &models.Folder{
			ID:   f.ID,
			Name: f.Name,
			Path: f.Path,
		})
	}

	return result, nil
}

func (s *MediaService) ValidateFolderPath(ctx context.Context, path string) error {
	return s.mediaRepo.Folders.ValidateOSPath(ctx, path)
}

func (s *MediaService) HandleNewAudioFile(ctx context.Context, path string) error {
	// For now, just log the detection
	s.logger.InfoContext(ctx, "New audio file detected",
		"path", path)

	// TODO: Implement actual file processing logic
	// - Extract metadata
	// - Add to database
	// - Process cover art
	// - etc.

	return nil
}

func (s *MediaService) GetUsersLibraries(ctx context.Context, userId string) ([]*models.Library, error) {
	dbLibraries, err := s.mediaRepo.Library.GetAllForUser(ctx, userId)
	if err != nil {
		return nil, appErr.HandleRepositoryError(err, "GetUsersLibraries", map[string]interface{}{
			"user_id": userId,
		})
	}

	var libraries []*models.Library
	for _, lib := range dbLibraries {
		libraries = append(libraries, &models.Library{
			ID:   lib.ID,
			Name: lib.Name,
		})
	}

	return libraries, nil
}

func (s *MediaService) GetCurrentLibrary(ctx context.Context, userId string) (*models.Library, error) {
	dbLibrary, err := s.mediaRepo.Library.GetCurrentForUser(ctx, userId)
	if err != nil {
		return nil, appErr.HandleRepositoryError(err, "GetCurrentLibrary", map[string]interface{}{
			"user_id": userId,
		})
	}

	library := &models.Library{
		ID:   dbLibrary.ID,
		Name: dbLibrary.Name,
	}

	return library, nil
}

func (s *MediaService) CreateLibrary(ctx context.Context, userId string, params models.CreateLibraryRequest) (*models.Library, error) {
	// Service-level validation
	if err := s.validator.Struct(params); err != nil {
		return nil, appErr.NewServiceError(appErr.ErrValidation, "invalid library request", err).
			WithOperation("CreateLibrary").
			WithData(map[string]interface{}{
				"user_id":   userId,
				"name":      params.Name,
				"n_folders": len(params.Folders),
			})
	}

	// Validate folders
	for i, folder := range params.Folders {
		if err := s.validator.Struct(folder); err != nil {
			return nil, appErr.NewServiceError(appErr.ErrValidation, "invalid folder configuration", err).
				WithOperation("CreateLibrary").
				WithData(map[string]interface{}{
					"folder_index": i,
					"folder_name":  folder.Name,
					"folder_path":  folder.Path,
				})
		}
	}

	// Create library
	dbLibrary, err := s.mediaRepo.Library.CreateLibrary(ctx, userId, params)
	if err != nil {
		return nil, appErr.HandleRepositoryError(err, "CreateLibrary", map[string]interface{}{
			"user_id":   userId,
			"name":      params.Name,
			"n_folders": len(params.Folders),
		})
	}

	s.logger.InfoContext(ctx, "Successfully created library",
		"user_id", userId,
		"library_id", dbLibrary.ID,
		"name", dbLibrary.Name,
		"n_folders", len(params.Folders))

	return &models.Library{
		ID:   dbLibrary.ID,
		Name: dbLibrary.Name,
	}, nil
}

func (s *MediaService) GetBooks(ctx context.Context, libraryId string, page, pageSize int) (*models.BookList, error) {
	if page < 1 {
		page = 1
	}
	if pageSize < 1 || pageSize > 100 {
		pageSize = 20 // Default page size
	}

	// Get books with pagination
	books, total, err := s.mediaRepo.Library.GetBooks(ctx, libraryId, (page-1)*pageSize, pageSize)
	if err != nil {
		return nil, appErr.HandleRepositoryError(err, "GetBooks", map[string]interface{}{
			"library_id": libraryId,
			"page":       page,
			"page_size":  pageSize,
		})
	}

	// Convert to ListAudiobook format
	listBooks := make([]models.ListAudiobook, 0, len(books))
	for _, book := range books {
		// Get the first author or create an empty one
		var author models.ListAuthor
		authors, err := book.QueryAuthors().All(ctx)
		if err != nil {
			s.logger.ErrorContext(ctx, "Failed to get authors for book",
				"book_id", book.ID,
				"error", err)
		} else if len(authors) > 0 {
			author = models.ListAuthor{
				ID:   authors[0].ID,
				Name: authors[0].Name,
			}
		}

		// Get cover information
		var cover models.Cover
		if bookCover, err := book.QueryCover().Only(ctx); err == nil {
			cover = models.Cover{
				Path:      bookCover.Path,
				Format:    bookCover.Format,
				Size:      bookCover.Size,
				UpdatedAt: bookCover.UpdatedAt,
			}
		}

		listBooks = append(listBooks, models.ListAudiobook{
			ID:     book.ID,
			Title:  book.Title,
			Cover:  cover,
			Author: author,
		})
	}

	return &models.BookList{
		Books: listBooks,
		Pagination: models.Pagination{
			CurrentPage: page,
			PageSize:    pageSize,
			TotalItems:  total,
			TotalPages:  (total + pageSize - 1) / pageSize,
		},
	}, nil
}
