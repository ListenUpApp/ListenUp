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

	r.Books = &bookRepository{client: client, logger: logger}
	r.Authors = &authorRepository{client: client, logger: logger}
	r.Narrators = &narratorRepository{client: client, logger: logger}

	return r
}
