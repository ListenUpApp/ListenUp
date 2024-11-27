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
