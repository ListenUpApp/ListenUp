package repository

import (
	"context"
	errorhandling "github.com/ListenUpApp/ListenUp/internal/error_handling"
	"log/slog"

	"github.com/ListenUpApp/ListenUp/internal/ent"
)

type ServerRepository struct {
	client *ent.Client
	logger *slog.Logger
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
			return nil, errorhandling.NewNotFoundError("no server instance found")
		}
		s.logger.ErrorContext(ctx, "Failed to query server",
			"error", err)
		return nil, errorhandling.NewInternalError(err, "failed to query server")
	}

	return server, nil
}

func (s *ServerRepository) CreateServer(ctx context.Context) (*ent.Server, error) {
	tx, err := s.client.Tx(ctx)
	if err != nil {
		s.logger.ErrorContext(ctx, "Failed to start transaction",
			"error", err)
		return nil, errorhandling.NewInternalError(err, "failed to start transaction")
	}
	defer tx.Rollback()

	exists, err := tx.Server.Query().Exist(ctx)
	if err != nil {
		s.logger.ErrorContext(ctx, "Failed to check server existence",
			"error", err)
		return nil, errorhandling.NewInternalError(err, "failed to check server existence")
	}

	if exists {
		return nil, errorhandling.NewConflictError("a server already exists")
	}

	// Create server config
	cfg, err := tx.ServerConfig.Create().
		SetName("ListenUp").
		Save(ctx)

	if err != nil {
		s.logger.ErrorContext(ctx, "Failed to create server config",
			"error", err)
		return nil, errorhandling.NewInternalError(err, "failed to create server config")
	}

	// Create server
	srv, err := tx.Server.Create().
		SetSetup(false).
		SetConfig(cfg).
		Save(ctx)

	if err != nil {
		s.logger.ErrorContext(ctx, "Failed to create server",
			"error", err)
		return nil, errorhandling.NewInternalError(err, "failed to create server")
	}

	// Commit transaction
	if err := tx.Commit(); err != nil {
		s.logger.ErrorContext(ctx, "Failed to commit transaction",
			"error", err)
		return nil, errorhandling.NewInternalError(err, "failed to commit transaction")
	}

	s.logger.InfoContext(ctx, "Server created successfully",
		"server_id", srv.ID)

	return srv, nil
}

func (s *ServerRepository) UpdateServerSetup(ctx context.Context, setup bool) (*ent.Server, error) {
	// Start a transaction
	tx, err := s.client.Tx(ctx)
	if err != nil {
		s.logger.ErrorContext(ctx, "Failed to start transaction",
			"error", err)
		return nil, errorhandling.NewInternalError(err, "failed to start transaction")
	}
	defer tx.Rollback()

	// Get the server first to ensure it exists
	server, err := tx.Server.Query().First(ctx)
	if err != nil {
		if ent.IsNotFound(err) {
			return nil, errorhandling.NewNotFoundError("no server instance found")
		}
		s.logger.ErrorContext(ctx, "Failed to query server",
			"error", err)
		return nil, errorhandling.NewInternalError(err, "failed to query server")
	}

	// Update the server
	server, err = server.Update().
		SetSetup(setup).
		Save(ctx)

	if err != nil {
		s.logger.ErrorContext(ctx, "Failed to update server setup status",
			"error", err)
		return nil, errorhandling.NewInternalError(err, "failed to update server setup status")
	}

	// Commit transaction
	if err := tx.Commit(); err != nil {
		s.logger.ErrorContext(ctx, "Failed to commit transaction",
			"error", err)
		return nil, errorhandling.NewInternalError(err, "failed to commit transaction")
	}

	s.logger.InfoContext(ctx, "Server setup status updated successfully",
		"server_id", server.ID,
		"setup", setup)

	return server, nil
}
