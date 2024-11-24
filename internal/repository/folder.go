package repository

import (
	"context"
	"log/slog"

	"github.com/ListenUpApp/ListenUp/internal/ent"
	"github.com/ListenUpApp/ListenUp/internal/ent/folder"
	errorhandling "github.com/ListenUpApp/ListenUp/internal/error_handling"
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
