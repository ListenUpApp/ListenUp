package content

import (
	"github.com/ListenUpApp/ListenUp/internal/ent"
	logging "github.com/ListenUpApp/ListenUp/internal/logger"
)

type Repository struct {
	Books     BookOperations
	Authors   AuthorOperations
	Narrators NarratorOperations
	client    *ent.Client
	logger    *logging.AppLogger
}

func NewRepository(client *ent.Client, logger *logging.AppLogger) *Repository {
	r := &Repository{
		client: client,
		logger: logger,
	}

	r.Books = newBookRepository(client, logger)
	r.Authors = newAuthorRepository(client, logger)
	r.Narrators = newNarratorRepository(client, logger)

	return r
}
