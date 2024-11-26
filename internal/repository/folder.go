package repository

import (
	"context"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"strings"

	"github.com/ListenUpApp/ListenUp/internal/ent"
	"github.com/ListenUpApp/ListenUp/internal/ent/folder"
	errorhandling "github.com/ListenUpApp/ListenUp/internal/error_handling"
	"github.com/ListenUpApp/ListenUp/internal/models"
)

type FolderRepository struct {
	client *ent.Client
	logger *slog.Logger
}

func NewFolderRepository(cfg Config) *FolderRepository {
	return &FolderRepository{
		client: cfg.Client,
		logger: cfg.Logger,
	}
}

var excludedPrefixes = []string{"/sys", "/proc", "/run", "/dev"}

func (f *FolderRepository) GetFolderById(ctx context.Context, id string) (*ent.Folder, error) {
	dbFolder, err := f.client.Folder.Query().
		Where(folder.IDEQ(id)).
		Only(ctx)

	if err != nil {
		if ent.IsNotFound(err) {
			return nil, errorhandling.NewNotFoundError("folder not found")
		}
		f.logger.ErrorContext(ctx, "Failed to get folder by ID",
			"folder_id", id,
			"error", err)
		return nil, errorhandling.NewInternalError(err, "failed to query folder")
	}

	return dbFolder, nil
}

// TODO switch this function over to using Validation methods
func (f *FolderRepository) CreateFolder(ctx context.Context, name string, path string) (*ent.Folder, error) {
	// Input validation
	if strings.TrimSpace(name) == "" {
		return nil, errorhandling.NewValidationError("Folder name cannot be empty").
			WithData(map[string]string{"name": "Folder name is required"})
	}

	if strings.TrimSpace(path) == "" {
		return nil, errorhandling.NewValidationError("Folder path cannot be empty").
			WithData(map[string]string{"path": "Folder path is required"})
	}

	// Start transaction
	tx, err := f.client.Tx(ctx)
	if err != nil {
		f.logger.ErrorContext(ctx, "Failed to start transaction for folder creation",
			"error", err)
		return nil, errorhandling.NewInternalError(err, "Failed to initiate folder creation")
	}

	// Check if folder with same path already exists
	exists, err := tx.Folder.Query().
		Where(folder.PathEQ(path)).
		Exist(ctx)
	if err != nil {
		f.logger.ErrorContext(ctx, "Failed to check folder existence",
			"path", path,
			"error", err)
		tx.Rollback()
		return nil, errorhandling.NewInternalError(err, "Failed to verify folder uniqueness")
	}
	if exists {
		tx.Rollback()
		return nil, errorhandling.NewConflictError(
			fmt.Sprintf("Folder with path '%s' already exists", path),
		).WithData(map[string]string{
			"path": "This folder path is already in use",
		})
	}

	// Create the folder
	dbFolder, err := tx.Folder.Create().
		SetName(name).
		SetPath(path).
		Save(ctx)

	if err != nil {
		f.logger.ErrorContext(ctx, "Failed to create folder",
			"name", name,
			"path", path,
			"error", err)
		tx.Rollback()
		return nil, errorhandling.NewInternalError(err, "Failed to create folder")
	}

	// Commit the transaction
	if err := tx.Commit(); err != nil {
		f.logger.ErrorContext(ctx, "Failed to commit folder creation transaction",
			"name", name,
			"path", path,
			"error", err)
		return nil, errorhandling.NewInternalError(err, "Failed to complete folder creation")
	}

	// Fetch the created folder to ensure we have the latest state
	createdFolder, err := f.client.Folder.
		Query().
		Where(folder.ID(dbFolder.ID)).
		Only(ctx)

	if err != nil {
		f.logger.ErrorContext(ctx, "Failed to fetch created folder",
			"id", dbFolder.ID,
			"error", err)
		return nil, errorhandling.NewInternalError(err, "Failed to verify folder creation")
	}

	f.logger.InfoContext(ctx, "Successfully created folder",
		"id", createdFolder.ID,
		"name", createdFolder.Name,
		"path", createdFolder.Path)

	return createdFolder, nil
}

func (f *FolderRepository) GetOSFolderWithDepth(ctx context.Context, path string, depth int) (*models.GetFolderResponse, error) {
	if err := f.ValidateOSPath(ctx, path); err != nil {
		return nil, err
	}

	return f.scanFolderRecursively(ctx, path, depth, 0)
}

func (f *FolderRepository) scanFolderRecursively(ctx context.Context, path string, maxDepth, currentDepth int) (*models.GetFolderResponse, error) {
	info, err := os.Stat(path)
	if err != nil {
		f.logger.ErrorContext(ctx, "Failed to get folder info",
			"path", path,
			"error", err)
		return nil, errorhandling.NewInternalError(err, "failed to get folder info")
	}

	result := &models.GetFolderResponse{
		Name:       info.Name(),
		Path:       path,
		Depth:      currentDepth,
		SubFolders: make([]models.GetFolderResponse, 0),
	}

	// If we've reached max depth or this isn't a directory, return
	if currentDepth >= maxDepth || !info.IsDir() {
		return result, nil
	}

	entries, err := os.ReadDir(path)
	if err != nil {
		f.logger.ErrorContext(ctx, "Failed to read directory",
			"path", path,
			"error", err)
		return nil, errorhandling.NewInternalError(err, "failed to read directory")
	}

	for _, entry := range entries {
		if !entry.IsDir() {
			continue
		}

		fullPath := filepath.Join(path, entry.Name())
		if f.shouldExcludePath(fullPath) {
			continue
		}

		subFolder, err := f.scanFolderRecursively(ctx, fullPath, maxDepth, currentDepth+1)
		if err != nil {
			continue // Log but don't fail the entire scan
		}

		result.SubFolders = append(result.SubFolders, *subFolder)
	}

	return result, nil
}

func (f *FolderRepository) ValidateOSPath(ctx context.Context, path string) error {
	if !filepath.IsAbs(path) {
		return errorhandling.NewValidationError("path must be absolute")
	}

	if f.shouldExcludePath(path) {
		return errorhandling.NewValidationError("path is in excluded directory")
	}

	// Check if path exists
	if _, err := os.Stat(path); err != nil {
		if os.IsNotExist(err) {
			return errorhandling.NewNotFoundError("path does not exist")
		}
		f.logger.ErrorContext(ctx, "Failed to check path",
			"path", path,
			"error", err)
		return errorhandling.NewInternalError(err, "failed to check path")
	}

	return nil
}

func (f *FolderRepository) shouldExcludePath(path string) bool {
	for _, prefix := range excludedPrefixes {
		if strings.HasPrefix(path, prefix) {
			return true
		}
	}
	return false
}
