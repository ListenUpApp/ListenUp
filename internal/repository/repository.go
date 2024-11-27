package repository

import (
	"fmt"

	"github.com/ListenUpApp/ListenUp/internal/ent"
	logging "github.com/ListenUpApp/ListenUp/internal/logger"
	"github.com/ListenUpApp/ListenUp/internal/repository/media"
)

type Repositories struct {
	User   *UserRepository
	Server *ServerRepository
	Media  *media.Repository
}

type Config struct {
	Client *ent.Client
	Logger *logging.AppLogger
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
		Media:  media.NewRepository(cfg.Client, cfg.Logger),
	}, nil
}
