package media

import (
	"context"

	"github.com/ListenUpApp/ListenUp/internal/ent"
	"github.com/ListenUpApp/ListenUp/internal/ent/folder"
	"github.com/ListenUpApp/ListenUp/internal/ent/library"
	"github.com/ListenUpApp/ListenUp/internal/ent/user"
	appErr "github.com/ListenUpApp/ListenUp/internal/error"
	logging "github.com/ListenUpApp/ListenUp/internal/logger"
	"github.com/ListenUpApp/ListenUp/internal/models"
)

type libraryRepository struct {
	client *ent.Client
	logger *logging.AppLogger
}

func (r *libraryRepository) GetLibraryByID(ctx context.Context, id string) (*ent.Library, error) {
	dbLibrary, err := r.client.Library.Query().
		Where(library.IDEQ(id)).
		Only(ctx)

	if err != nil {
		if ent.IsNotFound(err) {
			return nil, appErr.NewRepositoryError(appErr.ErrNotFound, "library not found", err).
				WithOperation("GetLibraryByID").
				WithData(map[string]interface{}{"library_id": id})
		}
		r.logger.ErrorContext(ctx, "Failed to get library by ID",
			"library_id", id,
			"error", err)
		return nil, appErr.NewRepositoryError(appErr.ErrDatabase, "failed to query library", err).
			WithOperation("GetLibraryByID").
			WithData(map[string]interface{}{"library_id": id})
	}

	return dbLibrary, nil
}

func (r *libraryRepository) GetCurrentForUser(ctx context.Context, userId string) (*ent.Library, error) {
	dbLibrary, err := r.client.User.Query().Where(user.IDEQ(userId)).QueryActiveLibrary().Only(ctx)

	if err != nil {
		if ent.IsNotFound(err) {
			return nil, appErr.NewRepositoryError(appErr.ErrNotFound, "no active library found", err).
				WithOperation("GetCurrentForUser").
				WithData(map[string]interface{}{"user_id": userId})
		}
		r.logger.ErrorContext(ctx, "Failed to query active library", "error", err)
		return nil, appErr.NewRepositoryError(appErr.ErrDatabase, "failed to query active library", err).
			WithOperation("GetCurrentForUser").
			WithData(map[string]interface{}{"user_id": userId})
	}

	return dbLibrary, nil
}

func (r *libraryRepository) GetAllForUser(ctx context.Context, userId string) ([]*ent.Library, error) {
	dbLibraries, err := r.client.User.Query().Where(user.IDEQ(userId)).QueryLibraries().All(ctx)

	if err != nil {
		if ent.IsNotFound(err) {
			return nil, appErr.NewRepositoryError(appErr.ErrNotFound, "no libraries found", err).
				WithOperation("GetAllForUser").
				WithData(map[string]interface{}{"user_id": userId})
		}
		r.logger.ErrorContext(ctx, "Failed to query user's libraries", "error", err)
		return nil, appErr.NewRepositoryError(appErr.ErrDatabase, "failed to query user's libraries", err).
			WithOperation("GetAllForUser").
			WithData(map[string]interface{}{"user_id": userId})
	}

	return dbLibraries, nil
}

func (r *libraryRepository) CreateLibrary(ctx context.Context, userId string, params models.CreateLibraryRequest) (*ent.Library, error) {
	tx, err := r.client.Tx(ctx)
	if err != nil {
		return nil, appErr.NewRepositoryError(appErr.ErrDatabase, "failed to start transaction", err).
			WithOperation("CreateLibrary")
	}
	defer tx.Rollback()

	// Check if library exists for user within transaction
	exists, err := tx.Library.Query().
		Where(
			library.HasUsersWith(user.ID(userId)),
			library.NameEqualFold(params.Name),
		).
		Exist(ctx)
	if err != nil {
		return nil, appErr.NewRepositoryError(appErr.ErrDatabase, "failed to check library existence", err).
			WithOperation("CreateLibrary").
			WithData(map[string]interface{}{
				"user_id": userId,
				"name":    params.Name,
			})
	}
	if exists {
		return nil, appErr.NewRepositoryError(appErr.ErrConflict, "a library with this name already exists", nil).
			WithOperation("CreateLibrary").
			WithField("name").
			WithData(map[string]interface{}{
				"user_id": userId,
				"name":    params.Name,
			})
	}

	// Create folders within transaction
	var dbFolders []*ent.Folder
	for _, folderParams := range params.Folders {
		newFolder, err := tx.Folder.Create().
			SetName(folderParams.Name).
			SetPath(folderParams.Path).
			Save(ctx)
		if err != nil {
			return nil, appErr.NewRepositoryError(appErr.ErrDatabase, "failed to create folder", err).
				WithOperation("CreateLibrary").
				WithData(map[string]interface{}{
					"folder_name": folderParams.Name,
					"folder_path": folderParams.Path,
				})
		}
		dbFolders = append(dbFolders, newFolder)
	}

	// Create library within transaction
	newLibrary, err := tx.Library.Create().
		SetName(params.Name).
		AddFolders(dbFolders...).
		Save(ctx)
	if err != nil {
		return nil, appErr.NewRepositoryError(appErr.ErrDatabase, "failed to create library", err).
			WithOperation("CreateLibrary").
			WithData(map[string]interface{}{
				"name":         params.Name,
				"folder_count": len(dbFolders),
			})
	}

	// Associate with user and set as active library
	err = tx.User.UpdateOneID(userId).
		AddLibraryIDs(newLibrary.ID).
		SetActiveLibrary(newLibrary).
		Exec(ctx)
	if err != nil {
		return nil, appErr.NewRepositoryError(appErr.ErrDatabase, "failed to associate library with user", err).
			WithOperation("CreateLibrary").
			WithData(map[string]interface{}{
				"user_id":    userId,
				"library_id": newLibrary.ID,
			})
	}

	if err := tx.Commit(); err != nil {
		return nil, appErr.NewRepositoryError(appErr.ErrDatabase, "failed to commit transaction", err).
			WithOperation("CreateLibrary").
			WithData(map[string]interface{}{
				"library_id": newLibrary.ID,
				"user_id":    userId,
			})
	}

	return newLibrary, nil
}

func (r *libraryRepository) AddFolders(ctx context.Context, libraryID string, folders []models.CreateFolderRequest) error {
	// Check if library exists
	lib, err := r.client.Library.Query().
		Where(library.ID(libraryID)).
		Only(ctx)
	if err != nil {
		if ent.IsNotFound(err) {
			return appErr.NewRepositoryError(appErr.ErrNotFound, "library not found", err).
				WithOperation("AddFolders").
				WithData(map[string]interface{}{"library_id": libraryID})
		}
		return appErr.NewRepositoryError(appErr.ErrDatabase, "failed to fetch library", err).
			WithOperation("AddFolders").
			WithData(map[string]interface{}{"library_id": libraryID})
	}

	tx, err := r.client.Tx(ctx)
	if err != nil {
		return appErr.NewRepositoryError(appErr.ErrDatabase, "failed to start transaction", err).
			WithOperation("AddFolders")
	}
	defer tx.Rollback()

	var dbFolders []*ent.Folder
	for _, folderRequest := range folders {
		newFolder, err := tx.Folder.Create().
			SetName(folderRequest.Name).
			SetPath(folderRequest.Path).
			Save(ctx)
		if err != nil {
			return appErr.NewRepositoryError(appErr.ErrDatabase, "failed to create folder", err).
				WithOperation("AddFolders").
				WithData(map[string]interface{}{
					"library_id":  libraryID,
					"folder_name": folderRequest.Name,
					"folder_path": folderRequest.Path,
				})
		}
		dbFolders = append(dbFolders, newFolder)
	}

	err = tx.Library.UpdateOne(lib).
		AddFolders(dbFolders...).
		Exec(ctx)
	if err != nil {
		return appErr.NewRepositoryError(appErr.ErrDatabase, "failed to add folders to library", err).
			WithOperation("AddFolders").
			WithData(map[string]interface{}{
				"library_id":   libraryID,
				"folder_count": len(dbFolders),
			})
	}

	if err := tx.Commit(); err != nil {
		return appErr.NewRepositoryError(appErr.ErrDatabase, "failed to commit transaction", err).
			WithOperation("AddFolders").
			WithData(map[string]interface{}{
				"library_id":   libraryID,
				"folder_count": len(dbFolders),
			})
	}

	return nil
}

func (r *libraryRepository) ExistsForUser(ctx context.Context, userId string, name string) (bool, error) {
	count, err := r.client.Library.Query().
		Where(
			library.HasUsersWith(user.ID(userId)),
			library.NameEqualFold(name),
		).
		Count(ctx)

	if err != nil {
		return false, appErr.NewRepositoryError(appErr.ErrDatabase, "failed to check library existence", err).
			WithOperation("ExistsForUser").
			WithData(map[string]interface{}{
				"user_id": userId,
				"name":    name,
			})
	}

	return count > 0, nil
}

func (r *libraryRepository) GetLibrariesWithFolders(ctx context.Context, folderPath string) ([]*ent.Library, error) {
	// Get libraries that contain the folder with this path
	libraries, err := r.client.Library.Query().
		Where(
			library.HasFoldersWith(
				folder.PathEQ(folderPath),
			),
		).
		WithFolders().
		All(ctx)

	if err != nil {
		if ent.IsNotFound(err) {
			return nil, appErr.NewRepositoryError(appErr.ErrNotFound, "no libraries found with folder", err).
				WithOperation("GetLibrariesWithFolders").
				WithData(map[string]interface{}{
					"folder_path": folderPath,
				})
		}
		return nil, appErr.NewRepositoryError(appErr.ErrDatabase, "failed to query libraries", err).
			WithOperation("GetLibrariesWithFolders").
			WithData(map[string]interface{}{
				"folder_path": folderPath,
			})
	}

	if len(libraries) == 0 {
		return nil, appErr.NewRepositoryError(appErr.ErrNotFound, "no libraries found with folder", nil).
			WithOperation("GetLibrariesWithFolders").
			WithData(map[string]interface{}{
				"folder_path": folderPath,
			})
	}

	return libraries, nil
}