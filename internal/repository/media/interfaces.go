package media

import (
	"context"

	"github.com/ListenUpApp/ListenUp/internal/ent"
	"github.com/ListenUpApp/ListenUp/internal/models"
)

type FolderOperations interface {
	GetByID(ctx context.Context, id string) (*ent.Folder, error)
	GetAll(ctx context.Context) ([]*ent.Folder, error)
	Create(ctx context.Context, params models.CreateFolderRequest) (*ent.Folder, error)
	GetOSFolderWithDepth(ctx context.Context, path string, depth int) (*models.GetFolderResponse, error)
	ValidateOSPath(ctx context.Context, path string) error
}

type LibraryOperations interface {
	GetLibraryByID(ctx context.Context, id string) (*ent.Library, error)
	GetCurrentForUser(ctx context.Context, userId string) (*ent.Library, error)
	GetAllForUser(ctx context.Context, userId string) ([]*ent.Library, error)
	CreateLibrary(ctx context.Context, userId string, params models.CreateLibraryRequest) (*ent.Library, error)
	AddFolders(ctx context.Context, libraryID string, folders []models.CreateFolderRequest) error
	ExistsForUser(ctx context.Context, userId string, name string) (bool, error)
}
