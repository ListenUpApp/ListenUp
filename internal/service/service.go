package service

import (
	"fmt"
	"github.com/ListenUpApp/ListenUp/internal/config"
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
	Image   *ImageService
}

type Deps struct {
	Repos     *repository.Repositories
	Logger    *logging.AppLogger
	Validator *validator.Validate
	Config    *config.Config
}

func NewServices(deps Deps) (*Services, error) {
	imageService, err := NewImageService(deps.Config.Metadata, deps.Logger)
	if err != nil {
		return nil, fmt.Errorf("failed to create image service: %w", err)
	}

	authService, err := NewAuthService(ServiceConfig{
		UserRepo:   deps.Repos.User,
		ServerRepo: deps.Repos.Server,
		Logger:     deps.Logger,
		Validator:  deps.Validator,
	})
	if err != nil {
		return nil, fmt.Errorf("failed to create auth service: %w", err)
	}

	serverService, err := NewServerService(ServiceConfig{
		ServerRepo: deps.Repos.Server,
		Logger:     deps.Logger,
	})
	if err != nil {
		return nil, fmt.Errorf("failed to create server service: %w", err)
	}

	userService, err := NewUserService(ServiceConfig{
		UserRepo: deps.Repos.User,
		Logger:   deps.Logger,
	})
	if err != nil {
		return nil, fmt.Errorf("failed to create user service: %w", err)
	}

	mediaService, err := NewMediaService(ServiceConfig{
		MediaRepo: deps.Repos.Media,
		UserRepo:  deps.Repos.User,
		Logger:    deps.Logger,
		Validator: deps.Validator,
	})
	if err != nil {
		return nil, fmt.Errorf("failed to create media service: %w", err)
	}

	contentService, err := NewContentService(ServiceConfig{
		MediaRepo:    deps.Repos.Media,
		ContentRepo:  deps.Repos.Content,
		ImageService: imageService,
		Logger:       deps.Logger,
		Validator:    deps.Validator,
	})
	if err != nil {
		return nil, fmt.Errorf("failed to create content service: %w", err)
	}

	return &Services{
		Auth:    authService,
		Server:  serverService,
		User:    userService,
		Media:   mediaService,
		Content: contentService,
		Image:   imageService,
	}, nil
}

// ServiceConfig shared configuration for services
type ServiceConfig struct {
	UserRepo     *repository.UserRepository
	ServerRepo   *repository.ServerRepository
	MediaRepo    *media.Repository
	ContentRepo  *content.Repository
	ImageService *ImageService
	Logger       *logging.AppLogger
	Validator    *validator.Validate
}
