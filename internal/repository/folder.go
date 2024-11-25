package repository

import (
	"context"
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

func (f *FolderRepository) CreateFolder(ctx context.Context, name string, path string) (*ent.Folder, error) {
	dbFolder, err := f.client.Folder.Create().
		SetName(name).
		SetPath(path).
		Save(ctx)

	if err != nil {
		f.logger.ErrorContext(ctx, "Failed to create folder",
			"name", name,
			"path", path,
			"error", err)
		return nil, errorhandling.NewInternalError(err, "failed to create folder")
	}

	return dbFolder, nil
}

func (f *FolderRepository) AddFolderToLibrary(ctx context.Context, folder *ent.Folder, library *ent.Library) error {
	_, err := library.Update().
		AddFolders(folder).
		Save(ctx)

	if err != nil {
		f.logger.ErrorContext(ctx, "Failed to add folder to library",
			"folder_id", folder.ID,
			"library_id", library.ID,
			"error", err)
		return errorhandling.NewInternalError(err, "failed to add folder to library")
	}
	return nil
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
