package service

import (
	"context"
	"fmt"

	"github.com/ListenUpApp/ListenUp/internal/ent"
	logging "github.com/ListenUpApp/ListenUp/internal/logger"	
	appErr "github.com/ListenUpApp/ListenUp/internal/error"
	"github.com/ListenUpApp/ListenUp/internal/repository"
)

type ServerService struct {
	serverRepo *repository.ServerRepository
	logger     *logging.AppLogger
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
	server, err := s.serverRepo.GetServer(ctx)
	if err != nil {
		return nil, appErr.HandleRepositoryError(err, "GetServer", map[string]interface{}{
			"operation": "server_retrieval",
		})
	}
	return server, nil
}

func (s *ServerService) CreateServer(ctx context.Context) (*ent.Server, error) {
	server, err := s.serverRepo.CreateServer(ctx)
	if err != nil {
		return nil, appErr.HandleRepositoryError(err, "CreateServer", map[string]interface{}{
			"operation": "server_creation",
		})
	}

	s.logger.InfoContext(ctx, "Server created successfully",
		"server_id", server.ID)

	return server, nil
}

func (s *ServerService) UpdateServerSetupStatus(ctx context.Context, setup bool) (*ent.Server, error) {
	server, err := s.serverRepo.UpdateServerSetup(ctx, setup)
	if err != nil {
		return nil, appErr.HandleRepositoryError(err, "UpdateServerSetupStatus", map[string]interface{}{
			"operation": "server_setup_update",
			"setup":     setup,
		})
	}

	s.logger.InfoContext(ctx, "Server setup status updated",
		"server_id", server.ID,
		"setup", setup)

	return server, nil
}
