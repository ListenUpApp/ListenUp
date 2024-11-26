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
	// Validate library name
	if params.Name == "" {
		return nil, errorhandling.NewValidationError("Library name is required")
	}

	// Validate folders
	if len(params.Folders) == 0 {
		return nil, errorhandling.NewValidationError("At least one folder must be selected")
	}

	// Check if library name already exists for user
	exists, err := l.libraryRepo.LibraryExistsForUser(ctx, userId, params.Name)
	if err != nil {
		return nil, errorhandling.NewInternalError(err, "Failed to check library existence")
	}
	if exists {
		return nil, errorhandling.NewConflictError("A library with this name already exists")
	}

	// Process folders
	var dbFolders []*ent.Folder
	for _, folder := range params.Folders {

		newFolder, err := l.folderRepo.CreateFolder(ctx, folder.Name, folder.Path)
		if err != nil {
			l.logger.ErrorContext(ctx, "Failed to create folder",
				"name", folder.Name,
				"path", folder.Path,
				"error", err)
			return nil, errorhandling.NewInternalError(err, "Failed to create folder")
		}
		dbFolders = append(dbFolders, newFolder)
	}

	// Create library
	newLibrary, err := l.libraryRepo.CreateLibrary(ctx, params.Name, dbFolders)
	if err != nil {
		l.logger.ErrorContext(ctx, "Failed to create library",
			"name", params.Name,
			"error", err)
		return nil, errorhandling.NewInternalError(err, "Failed to create library")
	}

	// Add library to user's libraries
	err = l.userRepo.AddLibraryToUser(ctx, userId, newLibrary.ID)
	if err != nil {
		l.logger.ErrorContext(ctx, "Failed to add library to user",
			"userId", userId,
			"libraryId", newLibrary.ID,
			"error", err)
		return nil, errorhandling.NewInternalError(err, "Failed to associate library with user")
	}

	// Update user's active library
	err = l.userRepo.UpdateActiveLibrary(ctx, userId, newLibrary.ID)
	if err != nil {
		l.logger.ErrorContext(ctx, "Failed to update user's active library",
			"userId", userId,
			"libraryId", newLibrary.ID,
			"error", err)
		return nil, errorhandling.NewInternalError(err, "Failed to update user settings")
	}

	return &models.Library{
		ID:   newLibrary.ID,
		Name: newLibrary.Name,
	}, nil
}
