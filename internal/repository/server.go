package repository

import (
	"context"

	appErr "github.com/ListenUpApp/ListenUp/internal/error"
	logging "github.com/ListenUpApp/ListenUp/internal/logger"

	"github.com/ListenUpApp/ListenUp/internal/ent"
)

type ServerRepository struct {
	client *ent.Client
	logger *logging.AppLogger
}

func NewServerRepository(cfg Config) *ServerRepository {
	return &ServerRepository{
		client: cfg.Client,
		logger: cfg.Logger,
	}
}

func (s *ServerRepository) GetServer(ctx context.Context) (*ent.Server, error) {
	server, err := s.client.Server.Query().First(ctx)
	if err != nil {
		if ent.IsNotFound(err) {
			return nil, appErr.NewRepositoryError(appErr.ErrNotFound, "no server instance found", err).
				WithOperation("GetServer")
		}
		s.logger.ErrorContext(ctx, "Failed to query server", "error", err)
		return nil, appErr.NewRepositoryError(appErr.ErrDatabase, "failed to query server", err).
			WithOperation("GetServer")
	}

	return server, nil
}

func (s *ServerRepository) CreateServer(ctx context.Context) (*ent.Server, error) {
	tx, err := s.client.Tx(ctx)
	if err != nil {
		s.logger.ErrorContext(ctx, "Failed to start transaction", "error", err)
		return nil, appErr.NewRepositoryError(appErr.ErrDatabase, "failed to start transaction", err).
			WithOperation("CreateServer")
	}
	defer tx.Rollback()

	exists, err := tx.Server.Query().Exist(ctx)
	if err != nil {
		s.logger.ErrorContext(ctx, "Failed to check server existence", "error", err)
		return nil, appErr.NewRepositoryError(appErr.ErrDatabase, "failed to check server existence", err).
			WithOperation("CreateServer")
	}

	if exists {
		return nil, appErr.NewRepositoryError(appErr.ErrConflict, "a server already exists", nil).
			WithOperation("CreateServer")
	}

	// Create server config
	cfg, err := tx.ServerConfig.Create().
		SetName("ListenUp").
		Save(ctx)

	if err != nil {
		s.logger.ErrorContext(ctx, "Failed to create server config", "error", err)
		return nil, appErr.NewRepositoryError(appErr.ErrDatabase, "failed to create server config", err).
			WithOperation("CreateServer").
			WithData(map[string]interface{}{
				"config_name": "ListenUp",
			})
	}

	// Create server
	srv, err := tx.Server.Create().
		SetSetup(false).
		SetConfig(cfg).
		Save(ctx)

	if err != nil {
		s.logger.ErrorContext(ctx, "Failed to create server", "error", err)
		return nil, appErr.NewRepositoryError(appErr.ErrDatabase, "failed to create server", err).
			WithOperation("CreateServer").
			WithData(map[string]interface{}{
				"config_id": cfg.ID,
			})
	}

	// Commit transaction
	if err := tx.Commit(); err != nil {
		s.logger.ErrorContext(ctx, "Failed to commit transaction", "error", err)
		return nil, appErr.NewRepositoryError(appErr.ErrDatabase, "failed to commit transaction", err).
			WithOperation("CreateServer").
			WithData(map[string]interface{}{
				"server_id": srv.ID,
				"config_id": cfg.ID,
			})
	}

	s.logger.InfoContext(ctx, "Server created successfully",
		"server_id", srv.ID)

	return srv, nil
}

func (s *ServerRepository) UpdateServerSetup(ctx context.Context, setup bool) (*ent.Server, error) {
	// Start a transaction
	tx, err := s.client.Tx(ctx)
	if err != nil {
		s.logger.ErrorContext(ctx, "Failed to start transaction", "error", err)
		return nil, appErr.NewRepositoryError(appErr.ErrDatabase, "failed to start transaction", err).
			WithOperation("UpdateServerSetup")
	}
	defer tx.Rollback()

	// Get the server first to ensure it exists
	server, err := tx.Server.Query().First(ctx)
	if err != nil {
		if ent.IsNotFound(err) {
			return nil, appErr.NewRepositoryError(appErr.ErrNotFound, "no server instance found", err).
				WithOperation("UpdateServerSetup")
		}
		s.logger.ErrorContext(ctx, "Failed to query server", "error", err)
		return nil, appErr.NewRepositoryError(appErr.ErrDatabase, "failed to query server", err).
			WithOperation("UpdateServerSetup")
	}

	// Update the server
	server, err = server.Update().
		SetSetup(setup).
		Save(ctx)

	if err != nil {
		s.logger.ErrorContext(ctx, "Failed to update server setup status", "error", err)
		return nil, appErr.NewRepositoryError(appErr.ErrDatabase, "failed to update server setup status", err).
			WithOperation("UpdateServerSetup").
			WithData(map[string]interface{}{
				"server_id": server.ID,
				"setup":     setup,
			})
	}

	// Commit transaction
	if err := tx.Commit(); err != nil {
		s.logger.ErrorContext(ctx, "Failed to commit transaction", "error", err)
		return nil, appErr.NewRepositoryError(appErr.ErrDatabase, "failed to commit transaction", err).
			WithOperation("UpdateServerSetup").
			WithData(map[string]interface{}{
				"server_id": server.ID,
				"setup":     setup,
			})
	}

	s.logger.InfoContext(ctx, "Server setup status updated successfully",
		"server_id", server.ID,
		"setup", setup)

	return server, nil
}
