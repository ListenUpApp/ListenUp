package media

import (
	"github.com/ListenUpApp/ListenUp/internal/ent"
	logging "github.com/ListenUpApp/ListenUp/internal/logger"
)

type Repository struct {
	Folders FolderOperations
	Library LibraryOperations
	client  *ent.Client
	logger  *logging.AppLogger
}

type Config struct {
	Client *ent.Client
	Logger *logging.AppLogger
}

func NewRepository(client *ent.Client, logger *logging.AppLogger) *Repository {
	r := &Repository{
		client: client,
		logger: logger,
	}

	// Initialize operations
	r.Folders = &folderRepository{client: client, logger: logger}
	r.Library = &libraryRepository{client: client, logger: logger}

	return r
}
