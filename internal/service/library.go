package service

import (
	"context"
	"errors"
	"fmt"
	"log/slog"

	errorhandling "github.com/ListenUpApp/ListenUp/internal/error_handling"
	"github.com/ListenUpApp/ListenUp/internal/models"
	"github.com/ListenUpApp/ListenUp/internal/repository"
)

type LibraryService struct {
	libraryRepo *repository.LibraryRepository
	logger      *slog.Logger
}

func NewLibraryService(cfg ServiceConfig) (*LibraryService, error) {
	if cfg.LibraryRepo == nil {
		return nil, fmt.Errorf("library repository is required")
	}
	if cfg.Logger == nil {
		return nil, fmt.Errorf("logger is required")
	}

	return &LibraryService{
		libraryRepo: cfg.LibraryRepo,
		logger:      cfg.Logger,
	}, nil
}

func (l *LibraryService) GetUsersLibraries(ctx context.Context, userId string) ([]*models.Library, error) {
	dbLibraries, err := l.libraryRepo.GetLibrariesByUserId(ctx, userId)

	if err != nil {
		var appErr *errorhandling.AppError
		if errors.As(err, &appErr) && appErr.Type == errorhandling.ErrorTypeNotFound {
			return nil, errorhandling.NewNotFoundError("No libraries found for user")
		} else {
			l.logger.ErrorContext(ctx, "failed to query libraries for user", "userId", userId)
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

func (l *LibraryService) GetCurrentLibrary(ctx context.Context, userId string) (*models.Library, error) {
	dbLibrary, err := l.libraryRepo.GetCurrentLibraryForUser(ctx, userId)

	if err != nil {
		var appErr *errorhandling.AppError
		if errors.As(err, &appErr) && appErr.Type == errorhandling.ErrorTypeNotFound {
			return nil, errorhandling.NewNotFoundError("No active library found for user")
		} else {
			l.logger.ErrorContext(ctx, "failed to query active library for user", "userId", userId)
			return nil, errorhandling.NewInternalError(err, "error checking user's active library")
		}
	}

	library := models.Library{
		ID:   dbLibrary.ID,
		Name: dbLibrary.Name,
	}

	return &library, nil
}
