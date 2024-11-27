package service

import (
	"context"
	"fmt"

	appErr "github.com/ListenUpApp/ListenUp/internal/error"
	logging "github.com/ListenUpApp/ListenUp/internal/logger"
	"github.com/ListenUpApp/ListenUp/internal/models"
	"github.com/ListenUpApp/ListenUp/internal/repository"
)

type UserService struct {
	userRepo *repository.UserRepository
	logger   *logging.AppLogger
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
		return nil, appErr.HandleRepositoryError(err, "GetUserByID", map[string]interface{}{
			"user_id": id,
		})
	}

	user := &models.User{
		ID:        dbUser.ID,
		FirstName: dbUser.FirstName,
		LastName:  dbUser.LastName,
		Email:     dbUser.Email,
	}

	return user, nil
}
