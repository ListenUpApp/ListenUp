package content

import (
	"context"

	"github.com/ListenUpApp/ListenUp/internal/ent"
	"github.com/ListenUpApp/ListenUp/internal/ent/author"
	appErr "github.com/ListenUpApp/ListenUp/internal/error"
	logging "github.com/ListenUpApp/ListenUp/internal/logger"
)

type authorRepository struct {
	client *ent.Client
	logger *logging.AppLogger
}

func (r *authorRepository) GetByName(ctx context.Context, name string) (*ent.Author, error) {
	dbAuthor, err := r.client.Author.Query().
		Where(author.NameEQ(name)).First(ctx)

	if err != nil {
		if ent.IsNotFound(err) {
			return nil, appErr.NewRepositoryError(appErr.ErrNotFound, "author not found", err).
				WithOperation("GetAuthorByName").
				WithData(map[string]interface{}{"name": name})
		}
		r.logger.ErrorContext(ctx, "Failed to get author by Name",
			"name", name,
			"error", err)
		return nil, appErr.NewRepositoryError(appErr.ErrDatabase, "failed to query author", err).
			WithOperation("GetAuthorByName").
			WithData(map[string]interface{}{"name": name})
	}

	return dbAuthor, nil
}

func (r *authorRepository) GetById(ctx context.Context, id string) (*ent.Author, error) {
	dbAuthor, err := r.client.Author.Query().
		Where(author.IDEQ(id)).Only(ctx)

	if err != nil {
		if ent.IsNotFound(err) {
			return nil, appErr.NewRepositoryError(appErr.ErrNotFound, "author not found", err).
				WithOperation("GetAuthorByName").
				WithData(map[string]interface{}{"author_id": id})
		}
		r.logger.ErrorContext(ctx, "Failed to get author by Name",
			"author_id", id,
			"error", err)
		return nil, appErr.NewRepositoryError(appErr.ErrDatabase, "failed to query author", err).
			WithOperation("GetAuthorByName").
			WithData(map[string]interface{}{"author_id": id})
	}

	return dbAuthor, nil
}
