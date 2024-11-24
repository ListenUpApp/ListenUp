package server

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"

	"github.com/go-playground/validator/v10"

	"github.com/ListenUpApp/ListenUp/internal/config"
	errorhandling "github.com/ListenUpApp/ListenUp/internal/error_handling"
	"github.com/ListenUpApp/ListenUp/internal/handler/api"
	"github.com/ListenUpApp/ListenUp/internal/handler/web"
	"github.com/ListenUpApp/ListenUp/internal/middleware"
	"github.com/ListenUpApp/ListenUp/internal/service"
	"github.com/ListenUpApp/ListenUp/internal/util"
	"github.com/ListenUpApp/ListenUp/internal/web/view/pages"
	"github.com/gin-gonic/gin"
)

type Server struct {
	config     *config.Config
	logger     *slog.Logger
	services   *service.Services
	router     *gin.Engine
	httpServer *http.Server
	validator  *validator.Validate
}

type Config struct {
	Config    *config.Config
	Logger    *slog.Logger
	Services  *service.Services
	Validator *validator.Validate
}

func New(cfg Config) *Server {
	util.InitializeAuth(cfg.Config.Cookie)

	router := gin.New()
	router.Use(gin.Recovery())
	router.Use(gin.Logger())
	router.Use(middleware.ErrorHandler(cfg.Logger))

	httpServer := &http.Server{
		Addr:    fmt.Sprintf(":%d", cfg.Config.Server.Port),
		Handler: router,
	}

	return &Server{
		config:     cfg.Config,
		logger:     cfg.Logger,
		services:   cfg.Services,
		router:     router, // Use the same router instance
		httpServer: httpServer,
		validator:  cfg.Validator,
	}
}

func (s *Server) Init(ctx context.Context) error {
	_, err := s.services.Server.GetServer(ctx)
	if err != nil {
		var appErr *errorhandling.AppError
		if errors.As(err, &appErr) && appErr.Type == errorhandling.ErrorTypeNotFound {
			// Server doesn't exist, let's create one
			_, err = s.services.Server.CreateServer(ctx)
			if err != nil {
				return fmt.Errorf("creating server: %w", err)
			}
		} else {
			// Any other error should cause initialization to fail
			return fmt.Errorf("checking server status: %w", err)
		}
	}

	s.setupRoutes()
	return nil
}

func (s *Server) setupRoutes() {
	// Static files
	s.router.Static("/static", "./internal/web/static")

	// API routes
	apiHandler := api.NewHandler(api.Config{
		Services: s.services,
		Logger:   s.logger,
		Config:   s.config,
	})

	apiGroup := s.router.Group("/api/v1")
	{
		// Public API routes
		apiHandler.RegisterPublicRoutes(apiGroup)

		// Protected API routes
		apiProtected := apiGroup.Group("")
		apiProtected.Use(middleware.APIAuth())
		apiProtected.Use(middleware.WithUser(s.services.User))
		apiProtected.Use(middleware.WithLibrary(s.services.Library))
		apiHandler.RegisterProtectedRoutes(apiProtected)
	}

	// Web routes
	webHandler := web.NewHandler(web.Config{
		Services:  s.services,
		Logger:    s.logger,
		Config:    s.config,
		Validator: s.validator,
	})

	// Public web routes (auth pages)
	webHandler.RegisterPublicRoutes(s.router.Group(""))

	// All other web routes are protected
	protected := s.router.Group("")
	protected.Use(middleware.WebAuth())
	protected.Use(middleware.WithUser(s.services.User))
	protected.Use(middleware.WithLibrary(s.services.Library))
	webHandler.RegisterProtectedRoutes(protected)

	s.router.NoRoute(func(c *gin.Context) {
		page := pages.NotFound("Not Found")
		err := page.Render(c, c.Writer)
		if err != nil {
			s.logger.Error("error rendering page",
				"error", err,
				"path", c.Request.URL.Path)
			c.String(500, "Error rendering page")
			return
		}
	})
}

func (s *Server) Run() error {
	s.logger.Info("Starting server",
		"port", s.config.Server.Port,
		"env", s.config.App.Environment)

	return s.httpServer.ListenAndServe()
}

func (s *Server) Shutdown(ctx context.Context) error {
	s.logger.Info("Shutting down server")
	return s.httpServer.Shutdown(ctx)
}
