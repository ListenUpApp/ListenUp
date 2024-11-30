package content

import (
	"context"

	"github.com/ListenUpApp/ListenUp/internal/ent"
	"github.com/ListenUpApp/ListenUp/internal/models"
)

type BookOperations interface {
	Create(ctx context.Context, bookID string, params models.CreateAudiobookRequest, folder *ent.Folder, library *ent.Library) (*ent.Book, error)
}

type AuthorOperations interface {
	GetByName(ctx context.Context, name string) (*ent.Author, error)
	GetById(ctx context.Context, id string) (*ent.Author, error)
}

type NarratorOperations interface {
	GetByName(ctx context.Context, name string) (*ent.Narrator, error)
	GetById(ctx context.Context, id string) (*ent.Narrator, error)
}
