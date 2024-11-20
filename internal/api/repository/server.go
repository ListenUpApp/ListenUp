package repository

import (
	"context"
	"fmt"

	"github.com/ListenUpApp/ListenUp/internal/ent"
)

type ServerRepository struct {
	client *ent.Client
}

func NewServerRepository(client *ent.Client) *ServerRepository {
	return &ServerRepository{
		client: client,
	}
}

func (s *ServerRepository) GetServer(ctx context.Context) (*ent.Server, error) {
	server, err := s.client.Server.Query().First(ctx)
	if err != nil {
		if ent.IsNotFound(err) {
			return nil, fmt.Errorf("No Server Instance Found")
		}
		return nil, fmt.Errorf("querying server: %w", err)
	}
	return server, nil
}

func (s *ServerRepository) CreateServer(ctx context.Context) (*ent.Server, error) {
	tx, err := s.client.Tx(ctx)

	if err != nil {
		return nil, fmt.Errorf("starting transaction: %w", err)
	}

	defer tx.Rollback()

	exists, err := tx.Server.Query().Exist(ctx)

	if err != nil {
		return nil, fmt.Errorf("checking server existence: %w", err)
	}

	if exists {
		return nil, fmt.Errorf("a server already exists")
	}

	cfg, err := tx.ServerConfig.Create().
		SetName("ListenUp").
		Save(ctx)

	if err != nil {
		return nil, fmt.Errorf("creating server config: %w", err)
	}

	srv, err := tx.Server.Create().
		SetSetup(false).
		SetConfig(cfg).
		Save(ctx)

	if err != nil {
		return nil, fmt.Errorf("creating server: %w", err)
	}

	// Commit transaction
	if err := tx.Commit(); err != nil {
		return nil, fmt.Errorf("committing transaction: %w", err)
	}

	return srv, nil
}
