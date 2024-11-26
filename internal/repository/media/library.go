package media

import (
	"context"
	"fmt"
	"log/slog"

	"github.com/ListenUpApp/ListenUp/internal/ent"
	"github.com/ListenUpApp/ListenUp/internal/ent/library"
	"github.com/ListenUpApp/ListenUp/internal/ent/user"
	errorhandling "github.com/ListenUpApp/ListenUp/internal/error_handling"
	"github.com/ListenUpApp/ListenUp/internal/models"
)

type libraryRepository struct {
	client *ent.Client
	logger *slog.Logger
}

func (r *libraryRepository) GetLibraryByID(ctx context.Context, id string) (*ent.Library, error) {
	dbLibrary, err := r.client.Library.Query().
		Where(library.IDEQ(id)).
		Only(ctx)

	if err != nil {
		if ent.IsNotFound(err) {
			return nil, errorhandling.NewNotFoundError("library not found")
		}
		r.logger.ErrorContext(ctx, "Failed to get library by ID",
			"library_id", id,
			"error", err)
		return nil, errorhandling.NewInternalError(err, "failed to query library")
	}

	return dbLibrary, nil
}

func (r *libraryRepository) GetCurrentForUser(ctx context.Context, userId string) (*ent.Library, error) {
	dbLibrary, err := r.client.User.Query().Where(user.IDEQ(userId)).QueryActiveLibrary().Only(ctx)

	if err != nil {
		if ent.IsNotFound(err) {
			return nil, errorhandling.NewNotFoundError("no library instance found")
		}
		r.logger.ErrorContext(ctx, "Failed to query active library",
			"error", err)
		return nil, errorhandling.NewInternalError(err, "failed to query active library")
	}

	return dbLibrary, nil

}

func (r *libraryRepository) GetAllForUser(ctx context.Context, userId string) ([]*ent.Library, error) {
	dbLibraries, err := r.client.User.Query().Where(user.IDEQ(userId)).QueryLibraries().All(ctx)

	if err != nil {
		if ent.IsNotFound(err) {
			return nil, errorhandling.NewNotFoundError("no libraries found on the existing user")
		}
		r.logger.ErrorContext(ctx, "Failed to query user's libraries",
			"error", err)
		return nil, errorhandling.NewInternalError(err, "failed to query user's libraries")
	}

	return dbLibraries, nil
}

// CreateLibrary creates a new library and associates it with existing folders
func (r *libraryRepository) CreateLibrary(ctx context.Context, userId string, params models.CreateLibraryRequest) (*ent.Library, error) {
	// Start transaction
	tx, err := r.client.Tx(ctx)
	if err != nil {
		return nil, errorhandling.NewInternalError(err, "Failed to start transaction")
	}
	defer func() {
		if v := recover(); v != nil {
			tx.Rollback()
			panic(v)
		}
	}()

	// Check if library exists for user within transaction
	exists, err := tx.Library.Query().
		Where(
			library.HasUsersWith(user.ID(userId)),
			library.NameEqualFold(params.Name),
		).
		Exist(ctx)
	if err != nil {
		tx.Rollback()
		return nil, errorhandling.NewInternalError(err, "Failed to check library existence")
	}
	if exists {
		tx.Rollback()
		return nil, errorhandling.NewConflictError("A library with this name already exists")
	}

	// Create folders within transaction
	var dbFolders []*ent.Folder
	for _, folderParams := range params.Folders {
		newFolder, err := tx.Folder.Create().
			SetName(folderParams.Name).
			SetPath(folderParams.Path).
			Save(ctx)
		if err != nil {
			tx.Rollback()
			return nil, errorhandling.NewInternalError(err, "Failed to create folder")
		}
		dbFolders = append(dbFolders, newFolder)
	}

	// Create library within transaction
	newLibrary, err := tx.Library.Create().
		SetName(params.Name).
		AddFolders(dbFolders...).
		Save(ctx)
	if err != nil {
		tx.Rollback()
		return nil, errorhandling.NewInternalError(err, "Failed to create library")
	}

	// Associate with user and set as active library
	err = tx.User.UpdateOneID(userId).
		AddLibraryIDs(newLibrary.ID).
		SetActiveLibrary(newLibrary).
		Exec(ctx)
	if err != nil {
		tx.Rollback()
		return nil, errorhandling.NewInternalError(err, "Failed to associate library with user")
	}

	if err := tx.Commit(); err != nil {
		return nil, errorhandling.NewInternalError(err, "Failed to commit transaction")
	}

	return newLibrary, nil
}

// AddFoldersToLibrary adds existing folders to an existing library
func (r *libraryRepository) AddFolders(ctx context.Context, libraryID string, folders []models.CreateFolderRequest) error {
	// Check if library exists
	lib, err := r.client.Library.Query().
		Where(library.ID(libraryID)).
		Only(ctx)
	if err != nil {
		if ent.IsNotFound(err) {
			return errorhandling.NewNotFoundError(fmt.Sprintf("Library with ID %s not found", libraryID))
		}
		return errorhandling.NewInternalError(err, "Failed to fetch library")
	}

	tx, err := r.client.Tx(ctx)
	if err != nil {
		return errorhandling.NewInternalError(err, "Failed to start transaction")
	}
	defer func() {
		if v := recover(); v != nil {
			tx.Rollback()
			panic(v)
		}
	}()

	var dbFolders []*ent.Folder
	for _, folderRequest := range folders {
		newFolder, err := tx.Folder.Create().
			SetName(folderRequest.Name).
			SetPath(folderRequest.Path).
			Save(ctx)
		if err != nil {
			tx.Rollback()
			return errorhandling.NewInternalError(err, "Failed to create folder")
		}
		dbFolders = append(dbFolders, newFolder)
	}

	err = tx.Library.UpdateOne(lib).
		AddFolders(dbFolders...).
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
func (r *libraryRepository) ExistsForUser(ctx context.Context, userId string, name string) (bool, error) {

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
