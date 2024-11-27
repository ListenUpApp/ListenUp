package media

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/ListenUpApp/ListenUp/internal/ent"
	"github.com/ListenUpApp/ListenUp/internal/ent/folder"
	appErr "github.com/ListenUpApp/ListenUp/internal/error"
	logging "github.com/ListenUpApp/ListenUp/internal/logger"
	"github.com/ListenUpApp/ListenUp/internal/models"
)

type folderRepository struct {
	client *ent.Client
	logger *logging.AppLogger
}

var excludedPrefixes = []string{"/sys", "/proc", "/run", "/dev"}

func (r *folderRepository) GetByID(ctx context.Context, id string) (*ent.Folder, error) {
	dbFolder, err := r.client.Folder.Query().
		Where(folder.IDEQ(id)).
		Only(ctx)

	if err != nil {
		if ent.IsNotFound(err) {
			return nil, appErr.NewRepositoryError(appErr.ErrNotFound, "folder not found", err).
				WithOperation("GetByID").
				WithField("id").
				WithData(map[string]interface{}{"folder_id": id})
		}
		r.logger.ErrorContext(ctx, "Failed to get folder by ID",
			"folder_id", id,
			"error", err)
		return nil, appErr.NewRepositoryError(appErr.ErrDatabase, "failed to query folder", err).
			WithOperation("GetByID").
			WithData(map[string]interface{}{"folder_id": id})
	}

	return dbFolder, nil
}

func (r *folderRepository) GetAll(ctx context.Context) ([]*ent.Folder, error) {
	dbFolders, err := r.client.Folder.Query().All(ctx)
	if err != nil {
		r.logger.ErrorContext(ctx, "Failed to get all folders", "error", err)
		return nil, appErr.NewRepositoryError(appErr.ErrDatabase, "failed to query all folders", err).
			WithOperation("GetAll")
	}

	return dbFolders, nil
}

func (r *folderRepository) Create(ctx context.Context, params models.CreateFolderRequest) (*ent.Folder, error) {
	// Input validation
	if strings.TrimSpace(params.Name) == "" {
		return nil, appErr.NewRepositoryError(appErr.ErrValidation, "folder name cannot be empty", nil).
			WithOperation("Create").
			WithField("name").
			WithData(map[string]interface{}{"name": "Folder name is required"})
	}

	if strings.TrimSpace(params.Path) == "" {
		return nil, appErr.NewRepositoryError(appErr.ErrValidation, "folder path cannot be empty", nil).
			WithOperation("Create").
			WithField("path").
			WithData(map[string]interface{}{"path": "Folder path is required"})
	}

	// Start transaction
	tx, err := r.client.Tx(ctx)
	if err != nil {
		r.logger.ErrorContext(ctx, "Failed to start transaction for folder creation", "error", err)
		return nil, appErr.NewRepositoryError(appErr.ErrDatabase, "failed to initiate folder creation", err).
			WithOperation("Create")
	}
	defer tx.Rollback()

	// Check if folder with same path already exists
	exists, err := tx.Folder.Query().
		Where(folder.PathEQ(params.Path)).
		Exist(ctx)
	if err != nil {
		r.logger.ErrorContext(ctx, "Failed to check folder existence",
			"path", params.Path,
			"error", err)
		return nil, appErr.NewRepositoryError(appErr.ErrDatabase, "failed to verify folder uniqueness", err).
			WithOperation("Create").
			WithData(map[string]interface{}{"path": params.Path})
	}
	if exists {
		return nil, appErr.NewRepositoryError(appErr.ErrConflict, fmt.Sprintf("folder with path '%s' already exists", params.Path), nil).
			WithOperation("Create").
			WithField("path").
			WithData(map[string]interface{}{"path": "This folder path is already in use"})
	}

	// Create the folder
	dbFolder, err := tx.Folder.Create().
		SetName(params.Name).
		SetPath(params.Path).
		Save(ctx)

	if err != nil {
		r.logger.ErrorContext(ctx, "Failed to create folder",
			"name", params.Name,
			"path", params.Path,
			"error", err)
		return nil, appErr.NewRepositoryError(appErr.ErrDatabase, "failed to create folder", err).
			WithOperation("Create").
			WithData(map[string]interface{}{
				"name": params.Name,
				"path": params.Path,
			})
	}

	// Commit the transaction
	if err := tx.Commit(); err != nil {
		r.logger.ErrorContext(ctx, "Failed to commit folder creation transaction",
			"name", params.Name,
			"path", params.Path,
			"error", err)
		return nil, appErr.NewRepositoryError(appErr.ErrDatabase, "failed to complete folder creation", err).
			WithOperation("Create").
			WithData(map[string]interface{}{
				"folder_id": dbFolder.ID,
				"name":      params.Name,
				"path":      params.Path,
			})
	}

	// Fetch the created folder to ensure we have the latest state
	createdFolder, err := r.client.Folder.
		Query().
		Where(folder.ID(dbFolder.ID)).
		Only(ctx)

	if err != nil {
		r.logger.ErrorContext(ctx, "Failed to fetch created folder",
			"id", dbFolder.ID,
			"error", err)
		return nil, appErr.NewRepositoryError(appErr.ErrDatabase, "failed to verify folder creation", err).
			WithOperation("Create").
			WithData(map[string]interface{}{"folder_id": dbFolder.ID})
	}

	r.logger.InfoContext(ctx, "Successfully created folder",
		"id", createdFolder.ID,
		"name", createdFolder.Name,
		"path", createdFolder.Path)

	return createdFolder, nil
}

func (r *folderRepository) GetOSFolderWithDepth(ctx context.Context, path string, depth int) (*models.GetFolderResponse, error) {
	if err := r.ValidateOSPath(ctx, path); err != nil {
		return nil, err
	}

	return r.scanFolderRecursively(ctx, path, depth, 0)
}

func (r *folderRepository) scanFolderRecursively(ctx context.Context, path string, maxDepth, currentDepth int) (*models.GetFolderResponse, error) {
	info, err := os.Stat(path)
	if err != nil {
		r.logger.ErrorContext(ctx, "Failed to get folder info",
			"path", path,
			"error", err)
		return nil, appErr.NewRepositoryError(appErr.ErrInternal, "failed to get folder info", err).
			WithOperation("scanFolderRecursively").
			WithData(map[string]interface{}{"path": path})
	}

	result := &models.GetFolderResponse{
		Name:       info.Name(),
		Path:       path,
		Depth:      currentDepth,
		SubFolders: make([]models.GetFolderResponse, 0),
	}

	if currentDepth >= maxDepth || !info.IsDir() {
		return result, nil
	}

	entries, err := os.ReadDir(path)
	if err != nil {
		r.logger.ErrorContext(ctx, "Failed to read directory",
			"path", path,
			"error", err)
		return nil, appErr.NewRepositoryError(appErr.ErrInternal, "failed to read directory", err).
			WithOperation("scanFolderRecursively").
			WithData(map[string]interface{}{"path": path})
	}

	for _, entry := range entries {
		if !entry.IsDir() {
			continue
		}

		fullPath := filepath.Join(path, entry.Name())
		if r.shouldExcludePath(fullPath) {
			continue
		}

		subFolder, err := r.scanFolderRecursively(ctx, fullPath, maxDepth, currentDepth+1)
		if err != nil {
			continue // Log but don't fail the entire scan
		}

		result.SubFolders = append(result.SubFolders, *subFolder)
	}

	return result, nil
}

func (r *folderRepository) ValidateOSPath(ctx context.Context, path string) error {
	if !filepath.IsAbs(path) {
		return appErr.NewRepositoryError(appErr.ErrValidation, "path must be absolute", nil).
			WithOperation("ValidateOSPath").
			WithField("path")
	}

	if r.shouldExcludePath(path) {
		return appErr.NewRepositoryError(appErr.ErrValidation, "path is in excluded directory", nil).
			WithOperation("ValidateOSPath").
			WithField("path")
	}

	if _, err := os.Stat(path); err != nil {
		if os.IsNotExist(err) {
			return appErr.NewRepositoryError(appErr.ErrNotFound, "path does not exist", err).
				WithOperation("ValidateOSPath").
				WithField("path").
				WithData(map[string]interface{}{"path": path})
		}
		r.logger.ErrorContext(ctx, "Failed to check path",
			"path", path,
			"error", err)
		return appErr.NewRepositoryError(appErr.ErrInternal, "failed to check path", err).
			WithOperation("ValidateOSPath").
			WithData(map[string]interface{}{"path": path})
	}

	return nil
}

func (r *folderRepository) shouldExcludePath(path string) bool {
	for _, prefix := range excludedPrefixes {
		if strings.HasPrefix(path, prefix) {
			return true
		}
	}
	return false
}
