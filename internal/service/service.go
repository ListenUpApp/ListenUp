package service

import (
	"log/slog"

	"github.com/ListenUpApp/ListenUp/internal/repository"
)

type Services struct {
	Auth   *AuthService
	Server *ServerService
	User   *UserService
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

	userService, err := NewUserService(ServiceConfig{
		UserRepo: deps.Repos.User,
		Logger:   deps.Logger,
	})

	if err != nil {
		return nil, err
	}

	return &Services{
		Auth:   authService,
		Server: serverService,
		User:   userService,
	}, nil
}

// ServiceConfig shared configuration for services
type ServiceConfig struct {
	UserRepo   *repository.UserRepository
	ServerRepo *repository.ServerRepository
	Logger     *slog.Logger
}
