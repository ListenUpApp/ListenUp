package service

import (
	"context"
	"fmt"
	"log/slog"

	"github.com/ListenUpApp/ListenUp/internal/models"
	"github.com/ListenUpApp/ListenUp/internal/repository"
)

type FolderService struct {
	folderRepo *repository.FolderRepository
	logger     *slog.Logger
}

func NewFolderService(cfg ServiceConfig) (*FolderService, error) {
	if cfg.FolderRepo == nil {
		return nil, fmt.Errorf("folder repository is required")
	}
	if cfg.Logger == nil {
		return nil, fmt.Errorf("logger is required")
	}

	return &FolderService{
		folderRepo: cfg.FolderRepo,
		logger:     cfg.Logger,
	}, nil
}

func (f *FolderService) GetFolderStructure(ctx context.Context, req models.GetFoldeRequest) (*models.GetFolderResponse, error) {
	return f.folderRepo.GetOSFolderWithDepth(ctx, req.Path, req.Depth)
}
