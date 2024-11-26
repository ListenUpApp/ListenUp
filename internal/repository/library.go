package repository

import (
	"context"
	"fmt"
	"log/slog"

	"github.com/ListenUpApp/ListenUp/internal/ent"
	"github.com/ListenUpApp/ListenUp/internal/ent/library"
	"github.com/ListenUpApp/ListenUp/internal/ent/user"
	errorhandling "github.com/ListenUpApp/ListenUp/internal/error_handling"
)

type LibraryRepository struct {
	client *ent.Client
	logger *slog.Logger
}

func NewLibraryRepository(cfg Config) *LibraryRepository {
	return &LibraryRepository{
		client: cfg.Client,
		logger: cfg.Logger,
	}
}

func (l *LibraryRepository) GetLibraryById(ctx context.Context, id string) (*ent.Library, error) {
	dbLibrary, err := l.client.Library.Query().
		Where(library.IDEQ(id)).
		Only(ctx)

	if err != nil {
		if ent.IsNotFound(err) {
			return nil, errorhandling.NewNotFoundError("library not found")
		}
		l.logger.ErrorContext(ctx, "Failed to get library by ID",
			"library_id", id,
			"error", err)
		return nil, errorhandling.NewInternalError(err, "failed to query library")
	}

	return dbLibrary, nil
}

func (l *LibraryRepository) GetCurrentLibraryForUser(ctx context.Context, userId string) (*ent.Library, error) {
	dbLibrary, err := l.client.User.Query().Where(user.IDEQ(userId)).QueryActiveLibrary().Only(ctx)

	if err != nil {
		if ent.IsNotFound(err) {
			return nil, errorhandling.NewNotFoundError("no library instance found")
		}
		l.logger.ErrorContext(ctx, "Failed to query active library",
			"error", err)
		return nil, errorhandling.NewInternalError(err, "failed to query active library")
	}

	return dbLibrary, nil

}

func (l *LibraryRepository) GetLibrariesByUserId(ctx context.Context, userId string) ([]*ent.Library, error) {
	dbLibraries, err := l.client.User.Query().Where(user.IDEQ(userId)).QueryLibraries().All(ctx)

	if err != nil {
		if ent.IsNotFound(err) {
			return nil, errorhandling.NewNotFoundError("no libraries found on the existing user")
		}
		l.logger.ErrorContext(ctx, "Failed to query user's libraries",
			"error", err)
		return nil, errorhandling.NewInternalError(err, "failed to query user's libraries")
	}

	return dbLibraries, nil
}

// CreateLibrary creates a new library and associates it with existing folders
func (l *LibraryRepository) CreateLibrary(ctx context.Context, name string, folders []*ent.Folder) (*ent.Library, error) {
	// Validate inputs
	if name == "" {
		return nil, errorhandling.NewValidationError("Library name cannot be empty").
			WithData(map[string]string{"name": "Library name is required"})
	}

	if len(folders) == 0 {
		return nil, errorhandling.NewValidationError("At least one folder is required").
			WithData(map[string]string{"folders": "At least one folder must be specified"})
	}

	// Check if library with same name exists
	exists, err := l.client.Library.Query().
		Where(library.NameEQ(name)).
		Exist(ctx)
	if err != nil {
		return nil, errorhandling.NewInternalError(err, "Failed to check library existence")
	}
	if exists {
		return nil, errorhandling.NewConflictError("A library with this name already exists")
	}

	// Start a transaction
	tx, err := l.client.Tx(ctx)
	if err != nil {
		return nil, errorhandling.NewInternalError(err, "Failed to start transaction")
	}

	// Create the library
	lib, err := tx.Library.
		Create().
		SetName(name).
		AddFolders(folders...). // Associate existing folders
		Save(ctx)

	if err != nil {
		tx.Rollback()
		return nil, errorhandling.NewInternalError(err, "Failed to create library")
	}

	// Commit the transaction
	if err := tx.Commit(); err != nil {
		return nil, errorhandling.NewInternalError(err, "Failed to commit transaction")
	}

	// Fetch the complete library with its folders
	result, err := l.client.Library.
		Query().
		Where(library.ID(lib.ID)).
		WithFolders().
		Only(ctx)

	if err != nil {
		if ent.IsNotFound(err) {
			return nil, errorhandling.NewNotFoundError("Library not found after creation")
		}
		return nil, errorhandling.NewInternalError(err, "Failed to fetch created library")
	}

	return result, nil
}

// AddFoldersToLibrary adds existing folders to an existing library
func (l *LibraryRepository) AddFoldersToLibrary(ctx context.Context, libraryID string, folders []*ent.Folder) error {
	// Check if library exists
	lib, err := l.client.Library.Query().
		Where(library.ID(libraryID)).
		Only(ctx)
	if err != nil {
		if ent.IsNotFound(err) {
			return errorhandling.NewNotFoundError(fmt.Sprintf("Library with ID %s not found", libraryID))
		}
		return errorhandling.NewInternalError(err, "Failed to fetch library")
	}

	// Start transaction
	tx, err := l.client.Tx(ctx)
	if err != nil {
		return errorhandling.NewInternalError(err, "Failed to start transaction")
	}

	// Update library with new folders
	err = tx.Library.UpdateOne(lib).
		AddFolders(folders...).
		Exec(ctx)

	if err != nil {
		tx.Rollback()
		return errorhandling.NewInternalError(err, "Failed to add folders to library")
	}

	if err := tx.Commit(); err != nil {
		return errorhandling.NewInternalError(err, "Failed to commit transaction")
	}

	return nil
}

// Check if a library with this name exists for the user
func (r *LibraryRepository) LibraryExistsForUser(ctx context.Context, userId string, name string) (bool, error) {

	count, err := r.client.Library.Query().
		Where(
			library.HasUsersWith(
				user.ID(userId),
			),
			library.NameEqualFold(name),
		).
		Count(ctx)

	if err != nil {
		return false, fmt.Errorf("failed to check library existence: %w", err)
	}

	return count > 0, nil
}
