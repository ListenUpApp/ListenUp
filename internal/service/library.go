package service

import (
	"context"
	"errors"
	"fmt"
	"log/slog"

	"github.com/ListenUpApp/ListenUp/internal/ent"
	errorhandling "github.com/ListenUpApp/ListenUp/internal/error_handling"
	"github.com/ListenUpApp/ListenUp/internal/models"
	"github.com/ListenUpApp/ListenUp/internal/repository"
)

type LibraryService struct {
	libraryRepo *repository.LibraryRepository
	folderRepo  *repository.FolderRepository
	userRepo    *repository.UserRepository
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
		userRepo:    cfg.UserRepo,
		folderRepo:  cfg.FolderRepo,
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

func (l *LibraryService) CreateLibrary(ctx context.Context, userId string, params models.CreateLibraryRequest) (*models.Library, error) {
	// Process folders
	var dbFolders []*ent.Folder
	for _, folder := range params.Folders {
		newFolder, err := l.folderRepo.CreateFolder(ctx, folder.Name, folder.Path)
		if err != nil {
			l.logger.ErrorContext(ctx, "Failed to create folder",
				"name", folder.Name,
				"path", folder.Path,
				"error", err)
			return nil, fmt.Errorf("failed to create folders: %w", err)
		}
		dbFolders = append(dbFolders, newFolder)
	}

	// Create library
	newLibrary, err := l.libraryRepo.CreateLibrary(ctx, params.Name, dbFolders)
	if err != nil {
		l.logger.ErrorContext(ctx, "Failed to create library",
			"name", params.Name,
			"error", err)
		return nil, fmt.Errorf("failed to create library: %w", err)
	}

	// Add library to user's libraries
	err = l.userRepo.AddLibraryToUser(ctx, userId, newLibrary.ID)
	if err != nil {
		l.logger.ErrorContext(ctx, "Failed to add library to user",
			"userId", userId,
			"libraryId", newLibrary.ID,
			"error", err)
		return nil, fmt.Errorf("failed to associate library with user: %w", err)
	}

	// Update user's active library
	err = l.userRepo.UpdateActiveLibrary(ctx, userId, newLibrary.ID)
	if err != nil {
		l.logger.ErrorContext(ctx, "Failed to update user's active library",
			"userId", userId,
			"libraryId", newLibrary.ID,
			"error", err)
		return nil, fmt.Errorf("failed to update user settings: %w", err)
	}

	return &models.Library{
		ID:   newLibrary.ID,
		Name: newLibrary.Name,
	}, nil
}
