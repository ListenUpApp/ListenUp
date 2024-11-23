package service

import (
	"github.com/ListenUpApp/ListenUp/internal/repository"
	"log/slog"
)

type Services struct {
	Auth   *AuthService
	Server *ServerService
}

type Deps struct {
	Repos  *repository.Repositories
	Logger *slog.Logger
}

func NewServices(deps Deps) (*Services, error) {
	authService, err := NewAuthService(ServiceConfig{
		UserRepo:   deps.Repos.User,
		ServerRepo: deps.Repos.Server,
		Logger:     deps.Logger,
	})

	serverService, err := NewServerService(ServiceConfig{
		ServerRepo: deps.Repos.Server,
		Logger:     deps.Logger,
	})

	if err != nil {
		return nil, err
	}

	return &Services{
		Auth:   authService,
		Server: serverService,
	}, nil
}

// ServiceConfig shared configuration for services
type ServiceConfig struct {
	UserRepo   *repository.UserRepository
	ServerRepo *repository.ServerRepository
	Logger     *slog.Logger
}
