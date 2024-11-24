package repository

import (
	"context"
	"log/slog"

	"github.com/ListenUpApp/ListenUp/internal/ent"
	"github.com/ListenUpApp/ListenUp/internal/ent/library"
	"github.com/ListenUpApp/ListenUp/internal/ent/user"
	errorhandling "github.com/ListenUpApp/ListenUp/internal/error_handling"
)

type LibraryRepository struct {
	client *ent.Client
	logger *slog.Logger
}

func NewLibraryRepository(cfg Config) *LibraryRepository {
	return &LibraryRepository{
		client: cfg.Client,
		logger: cfg.Logger,
	}
}

func (l *LibraryRepository) GetLibraryById(ctx context.Context, id string) (*ent.Library, error) {
	dbLibrary, err := l.client.Library.Query().
		Where(library.IDEQ(id)).
		Only(ctx)

	if err != nil {
		if ent.IsNotFound(err) {
			return nil, errorhandling.NewNotFoundError("library not found")
		}
		l.logger.ErrorContext(ctx, "Failed to get library by ID",
			"library_id", id,
			"error", err)
		return nil, errorhandling.NewInternalError(err, "failed to query library")
	}

	return dbLibrary, nil
}

func (l *LibraryRepository) GetCurrentLibraryForUser(ctx context.Context, userId string) (*ent.Library, error) {
	dbLibrary, err := l.client.User.Query().Where(user.IDEQ(userId)).QueryActiveLibrary().Only(ctx)

	if err != nil {
		if ent.IsNotFound(err) {
			return nil, errorhandling.NewNotFoundError("no library instance found")
		}
		l.logger.ErrorContext(ctx, "Failed to query active library",
			"error", err)
		return nil, errorhandling.NewInternalError(err, "failed to query active library")
	}

	return dbLibrary, nil

}

func (l *LibraryRepository) GetLibrariesByUserId(ctx context.Context, userId string) ([]*ent.Library, error) {
	dbLibraries, err := l.client.User.Query().Where(user.IDEQ(userId)).QueryLibraries().All(ctx)

	if err != nil {
		if ent.IsNotFound(err) {
			return nil, errorhandling.NewNotFoundError("no libraries found on the existing user")
		}
		l.logger.ErrorContext(ctx, "Failed to query user's libraries",
			"error", err)
		return nil, errorhandling.NewInternalError(err, "failed to query user's libraries")
	}

	return dbLibraries, nil
}