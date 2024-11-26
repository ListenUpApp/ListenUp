package media

import (
	"log/slog"

	"github.com/ListenUpApp/ListenUp/internal/ent"
)

type Repository struct {
	Folders FolderOperations
	Library LibraryOperations
	client  *ent.Client
	logger  *slog.Logger
}

type Config struct {
	Client *ent.Client
	Logger *slog.Logger
}

func NewRepository(client *ent.Client, logger *slog.Logger) *Repository {
	r := &Repository{
		client: client,
		logger: logger,
	}

	// Initialize operations
	r.Folders = &folderRepository{client: client, logger: logger}
	r.Library = &libraryRepository{client: client, logger: logger}

	return r
}
