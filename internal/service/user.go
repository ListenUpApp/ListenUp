package service

import (
	"context"
	"fmt"
	"log/slog"

	"github.com/ListenUpApp/ListenUp/internal/models"
	"github.com/ListenUpApp/ListenUp/internal/repository"
)

type UserService struct {
	userRepo *repository.UserRepository
	logger   *slog.Logger
}

func NewUserService(cfg ServiceConfig) (*UserService, error) {
	if cfg.UserRepo == nil {
		return nil, fmt.Errorf("user repository is required")
	}
	if cfg.Logger == nil {
		return nil, fmt.Errorf("logger is required")
	}

	return &UserService{
		userRepo: cfg.UserRepo,
		logger:   cfg.Logger,
	}, nil
}

func (s *UserService) GetUserByID(ctx context.Context, id string) (*models.User, error) {
	dbUser, err := s.userRepo.GetUserById(ctx, id)
	if err != nil {
		return nil, fmt.Errorf("failed to get user by ID: %w", err)
	}

	user := &models.User{
		ID:        dbUser.ID,
		FirstName: dbUser.FirstName,
		LastName:  dbUser.LastName,
		Email:     dbUser.Email,
	}

	return user, nil
}
