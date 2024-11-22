package service

import (
	"context"
	"fmt"
	"github.com/ListenUpApp/ListenUp/internal/ent"
	"github.com/ListenUpApp/ListenUp/internal/repository"
	"log/slog"
)

type ServerService struct {
	serverRepo *repository.ServerRepository
	logger     *slog.Logger
}

func NewServerService(cfg ServiceConfig) (*ServerService, error) {
	if cfg.ServerRepo == nil {
		return nil, fmt.Errorf("server repository is required")
	}
	if cfg.Logger == nil {
		return nil, fmt.Errorf("logger is required")
	}
	return &ServerService{
		serverRepo: cfg.ServerRepo,
		logger:     cfg.Logger,
	}, nil
}

func (s *ServerService) GetServer(ctx context.Context) (*ent.Server, error) {
	return s.serverRepo.GetServer(ctx)
}

func (s *ServerService) CreateServer(ctx context.Context) (*ent.Server, error) {
	return s.serverRepo.CreateServer(ctx)
}
