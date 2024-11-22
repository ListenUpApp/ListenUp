package repository

import (
	"fmt"
	"github.com/ListenUpApp/ListenUp/internal/ent"
	"log/slog"
)

type Repositories struct {
	User   *UserRepository
	Server *ServerRepository
}

type Config struct {
	Client *ent.Client
	Logger *slog.Logger
}

func NewRepositories(cfg Config) (*Repositories, error) {
	if cfg.Client == nil {
		return nil, fmt.Errorf("database client is required")
	}
	if cfg.Logger == nil {
		return nil, fmt.Errorf("logger is required")
	}

	return &Repositories{
		User:   NewUserRepository(cfg),
		Server: NewServerRepository(cfg),
	}, nil
}