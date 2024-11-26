package service

import (
	"context"
	"errors"
	"fmt"
	"log/slog"

	"github.com/ListenUpApp/ListenUp/internal/ent/library"
	errorhandling "github.com/ListenUpApp/ListenUp/internal/error_handling"
	"github.com/ListenUpApp/ListenUp/internal/models"
	"github.com/ListenUpApp/ListenUp/internal/repository"
	"github.com/ListenUpApp/ListenUp/internal/repository/media"
	"github.com/go-playground/validator/v10"
)

type MediaService struct {
	mediaRepo *media.Repository
	userRepo  *repository.UserRepository
	logger    *slog.Logger
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
	return s.mediaRepo.Folders.GetOSFolderWithDepth(ctx, req.Path, req.Depth)
}

func (s *MediaService) GetUsersLibraries(ctx context.Context, userId string) ([]*models.Library, error) {
	dbLibraries, err := s.mediaRepo.Library.GetAllForUser(ctx, userId)

	if err != nil {
		var appErr *errorhandling.AppError
		if errors.As(err, &appErr) && appErr.Type == errorhandling.ErrorTypeNotFound {
			return nil, errorhandling.NewNotFoundError("No libraries found for user")
		} else {
			s.logger.ErrorContext(ctx, "failed to query libraries for user", "userId", userId)
			return nil, errorhandling.NewInternalError(err, "error checking user's libraries")
		}
	}

	var libraries []*models.Library

	for _, library := range dbLibraries {

		newLibrary := models.Library{
			ID:   library.ID,
			Name: library.Name,
		}

		libraries = append(libraries, &newLibrary)
	}

	return libraries, nil

}

func (s *MediaService) GetCurrentLibrary(ctx context.Context, userId string) (*models.Library, error) {
	dbLibrary, err := s.mediaRepo.Library.GetCurrentForUser(ctx, userId)

	if err != nil {
		var appErr *errorhandling.AppError
		if errors.As(err, &appErr) && appErr.Type == errorhandling.ErrorTypeNotFound {
			return nil, errorhandling.NewNotFoundError("No active library found for user")
		} else {
			s.logger.ErrorContext(ctx, "failed to query active library for user", "userId", userId)
			return nil, errorhandling.NewInternalError(err, "error checking user's active library")
		}
	}

	library := models.Library{
		ID:   dbLibrary.ID,
		Name: dbLibrary.Name,
	}

	return &library, nil
}

func (s *MediaService) CreateLibrary(ctx context.Context, userId string, params models.CreateLibraryRequest) (*models.Library, error) {
	// Service-level validation
	if err := s.validator.Struct(params); err != nil {
		return nil, errorhandling.NewValidationError("Invalid library request").
			WithData(map[string]string{"validation": err.Error()})
	}

	// Call repository method which handles the transaction
	dbibrary, err := s.mediaRepo.Library.CreateLibrary(ctx, userId, params)
	if err != nil {
		s.logger.ErrorContext(ctx, "Failed to create library",
			"userId", userId,
			"name", params.Name,
			"error", err)
		return nil, err // Repository already wraps errors appropriately
	}

	s.logger.InfoContext(ctx, "Successfully created library",
		"userId", userId,
		"libraryId", library.ID,
		"name", library.Name)

	library := models.Library{
		ID:   dbibrary.ID,
		Name: dbibrary.Name,
	}

	return &library, nil
}
