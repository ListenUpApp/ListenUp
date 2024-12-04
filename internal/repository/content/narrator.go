package content

import (
	"context"
	"github.com/ListenUpApp/ListenUp/internal/ent"
	"github.com/ListenUpApp/ListenUp/internal/ent/narrator"
	appErr "github.com/ListenUpApp/ListenUp/internal/error"
	logging "github.com/ListenUpApp/ListenUp/internal/logger"
)

type narratorRepository struct {
	client *ent.Client
	logger *logging.AppLogger
}

func newNarratorRepository(client *ent.Client, logger *logging.AppLogger) *narratorRepository {
	return &narratorRepository{
		client: client,
		logger: logger,
	}
}

func (r *narratorRepository) GetByName(ctx context.Context, name string) (*ent.Narrator, error) {
	dbnarrator, err := r.client.Narrator.Query().
		Where(narrator.NameEQ(name)).First(ctx)

	if err != nil {
		if ent.IsNotFound(err) {
			return nil, appErr.NewRepositoryError(appErr.ErrNotFound, "narrator not found", err).
				WithOperation("GetnarratorByName").
				WithData(map[string]interface{}{"name": name})
		}
		r.logger.ErrorContext(ctx, "Failed to get narrator by Name",
			"name", name,
			"error", err)
		return nil, appErr.NewRepositoryError(appErr.ErrDatabase, "failed to query narrator", err).
			WithOperation("GetnarratorByName").
			WithData(map[string]interface{}{"name": name})
	}

	return dbnarrator, nil
}

func (r *narratorRepository) GetById(ctx context.Context, id string) (*ent.Narrator, error) {
	dbnarrator, err := r.client.Narrator.Query().
		Where(narrator.IDEQ(id)).Only(ctx)

	if err != nil {
		if ent.IsNotFound(err) {
			return nil, appErr.NewRepositoryError(appErr.ErrNotFound, "narrator not found", err).
				WithOperation("GetnarratorByName").
				WithData(map[string]interface{}{"narrator_id": id})
		}
		r.logger.ErrorContext(ctx, "Failed to get narrator by Name",
			"narrator_id", id,
			"error", err)
		return nil, appErr.NewRepositoryError(appErr.ErrDatabase, "failed to query narrator", err).
			WithOperation("GetnarratorByName").
			WithData(map[string]interface{}{"narrator_id": id})
	}

	return dbnarrator, nil
}
