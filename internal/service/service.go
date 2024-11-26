package service

import (
	"log/slog"

	"github.com/ListenUpApp/ListenUp/internal/repository"
	"github.com/go-playground/validator/v10"
)

type Services struct {
	Auth    *AuthService
	Server  *ServerService
	User    *UserService
	Library *LibraryService
	Folder  *FolderService
}

type Deps struct {
	Repos     *repository.Repositories
	Logger    *slog.Logger
	Validator *validator.Validate
}

func NewServices(deps Deps) (*Services, error) {
	authService, err := NewAuthService(ServiceConfig{
		UserRepo:   deps.Repos.User,
		ServerRepo: deps.Repos.Server,
		Logger:     deps.Logger,
		Validator:  deps.Validator,
	})

	serverService, err := NewServerService(ServiceConfig{
		ServerRepo: deps.Repos.Server,
		Logger:     deps.Logger,
	})

	userService, err := NewUserService(ServiceConfig{
		UserRepo: deps.Repos.User,
		Logger:   deps.Logger,
	})
	libraryService, err := NewLibraryService(ServiceConfig{
		LibraryRepo: deps.Repos.Library,
		FolderRepo:  deps.Repos.Folder,
		UserRepo:    deps.Repos.User,
		Logger:      deps.Logger,
	})

	folderService, err := NewFolderService(ServiceConfig{
		FolderRepo: deps.Repos.Folder,
		Logger:     deps.Logger,
	})

	if err != nil {
		return nil, err
	}

	return &Services{
		Auth:    authService,
		Server:  serverService,
		User:    userService,
		Library: libraryService,
		Folder:  folderService,
	}, nil
}

// ServiceConfig shared configuration for services
type ServiceConfig struct {
	UserRepo    *repository.UserRepository
	ServerRepo  *repository.ServerRepository
	LibraryRepo *repository.LibraryRepository
	FolderRepo  *repository.FolderRepository
	Logger      *slog.Logger
	Validator   *validator.Validate
}
