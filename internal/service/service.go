package service

import (
	logging "github.com/ListenUpApp/ListenUp/internal/logger"
	"github.com/ListenUpApp/ListenUp/internal/repository"
	"github.com/ListenUpApp/ListenUp/internal/repository/content"
	"github.com/ListenUpApp/ListenUp/internal/repository/media"
	"github.com/go-playground/validator/v10"
)

type Services struct {
	Auth    *AuthService
	Server  *ServerService
	User    *UserService
	Media   *MediaService
	Content *ContentService
}

type Deps struct {
	Repos     *repository.Repositories
	Logger    *logging.AppLogger
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
	mediaService, err := NewMediaService(ServiceConfig{
		MediaRepo: deps.Repos.Media,
		UserRepo:  deps.Repos.User,
		Logger:    deps.Logger,
		Validator: deps.Validator,
	})

	contentService, err := NewContentService(ServiceConfig{
		MediaRepo:   deps.Repos.Media,
		ContentRepo: deps.Repos.Content,
		Logger:      deps.Logger,
		Validator:   deps.Validator,
	})

	if err != nil {
		return nil, err
	}

	return &Services{
		Auth:    authService,
		Server:  serverService,
		User:    userService,
		Media:   mediaService,
		Content: contentService,
	}, nil
}

// ServiceConfig shared configuration for services
type ServiceConfig struct {
	UserRepo    *repository.UserRepository
	ServerRepo  *repository.ServerRepository
	MediaRepo   *media.Repository
	ContentRepo *content.Repository
	Logger      *logging.AppLogger
	Validator   *validator.Validate
}
