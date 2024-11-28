package server

import (
	"context"
	"fmt"
	"net/http"

	"github.com/go-playground/validator/v10"

	"github.com/ListenUpApp/ListenUp/internal/config"
	appErr "github.com/ListenUpApp/ListenUp/internal/error"
	"github.com/ListenUpApp/ListenUp/internal/handler/api"
	"github.com/ListenUpApp/ListenUp/internal/handler/web"
	logging "github.com/ListenUpApp/ListenUp/internal/logger"
	"github.com/ListenUpApp/ListenUp/internal/middleware"
	"github.com/ListenUpApp/ListenUp/internal/scanner"
	"github.com/ListenUpApp/ListenUp/internal/service"
	"github.com/ListenUpApp/ListenUp/internal/util"
	"github.com/ListenUpApp/ListenUp/internal/web/view/pages"
	"github.com/gin-gonic/gin"
)

type Server struct {
	config     *config.Config
	logger     *logging.AppLogger
	services   *service.Services
	router     *gin.Engine
	httpServer *http.Server
	validator  *validator.Validate
	scanner    *scanner.Scanner
}

type Config struct {
	Config    *config.Config
	Logger    *logging.AppLogger
	Services  *service.Services
	Validator *validator.Validate
}

func New(cfg Config) (*Server, error) {
	if cfg.Config == nil {
		return nil, appErr.NewHandlerError(appErr.ErrValidation, "config is required", nil).
			WithOperation("NewServer")
	}
	if cfg.Logger == nil {
		return nil, appErr.NewHandlerError(appErr.ErrValidation, "logger is required", nil).
			WithOperation("NewServer")
	}
	if cfg.Services == nil {
		return nil, appErr.NewHandlerError(appErr.ErrValidation, "services are required", nil).
			WithOperation("NewServer")
	}

	util.InitializeAuth(cfg.Config.Cookie)

	router := gin.New()
	router.Use(gin.Recovery())

	// Create request logger
	reqLogger := middleware.NewRequestLogger(cfg.Logger)
	router.Use(reqLogger.Logger())

	httpServer := &http.Server{
		Addr:    fmt.Sprintf(":%d", cfg.Config.Server.Port),
		Handler: router,
	}
	scanner, err := scanner.New(scanner.Config{
		Logger:         cfg.Logger,
		ContentService: cfg.Services.Content,
		MediaService: cfg.Services.Media,
	})
	if err != nil {
		return nil, appErr.NewHandlerError(appErr.ErrInternal, "failed to create scanner", err).
			WithOperation("NewServer")
	}

	// Initialize scanner with existing folders
	if err := scanner.InitializeFromDB(context.Background()); err != nil {
		cfg.Logger.Error("Failed to initialize scanner with existing folders", "error", err)
	}

	return &Server{
		config:     cfg.Config,
		logger:     cfg.Logger,
		services:   cfg.Services,
		router:     router,
		httpServer: httpServer,
		validator:  cfg.Validator,
		scanner:    scanner,
	}, nil
}

func (s *Server) Init(ctx context.Context) error {
	_, err := s.services.Server.GetServer(ctx)
	if err != nil {
		if appErr.IsNotFound(err) {
			// Server doesn't exist, create one
			_, err = s.services.Server.CreateServer(ctx)
			if err != nil {
				return appErr.NewHandlerError(appErr.ErrInternal, "failed to create server", err).
					WithOperation("ServerInit")
			}
		} else {
			return appErr.NewHandlerError(appErr.ErrInternal, "failed to check server status", err).
				WithOperation("ServerInit")
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
		apiHandler.RegisterPublicRoutes(apiGroup)

		apiProtected := apiGroup.Group("")
		apiProtected.Use(middleware.APIAuth())
		apiProtected.Use(middleware.WithUser(s.services.User))
		apiProtected.Use(middleware.WithLibrary(s.services.Media))
		apiHandler.RegisterProtectedRoutes(apiProtected)
	}

	// Web routes
	webHandler := web.NewHandler(web.Config{
		Services:  s.services,
		Logger:    s.logger,
		Config:    s.config,
		Validator: s.validator,
	})

	webHandler.RegisterPublicRoutes(s.router.Group(""))

	protected := s.router.Group("")
	protected.Use(middleware.WebAuth())
	protected.Use(middleware.WithUser(s.services.User))
	protected.Use(middleware.WithLibrary(s.services.Media))
	webHandler.RegisterProtectedRoutes(protected)

	// 404 handler
	s.router.NoRoute(s.handleNotFound)
}

func (s *Server) handleNotFound(c *gin.Context) {
	page := pages.NotFound()
	if err := page.Render(c, c.Writer); err != nil {
		s.logger.ErrorContext(c.Request.Context(), "Failed to render 404 page",
			"error", err,
			"path", c.Request.URL.Path)

		renderErr := appErr.NewHandlerError(appErr.ErrInternal, "failed to render page", err).
			WithOperation("NotFoundHandler").
			WithData(map[string]interface{}{
				"path": c.Request.URL.Path,
			})

		c.JSON(http.StatusInternalServerError, gin.H{
			"success": false,
			"error": gin.H{
				"message": renderErr.Message,
			},
		})
	}
}

func (s *Server) Run() error {
	s.logger.Info("Starting server",
		"port", s.config.Server.Port,
		"env", s.config.App.Environment)

	s.scanner.Start()

	if err := s.httpServer.ListenAndServe(); err != nil && err != http.ErrServerClosed {
		return appErr.NewHandlerError(appErr.ErrInternal, "server failed to start", err).
			WithOperation("ServerRun").
			WithData(map[string]interface{}{
				"port": s.config.Server.Port,
				"env":  s.config.App.Environment,
			})
	}
	return nil
}

func (s *Server) Shutdown(ctx context.Context) error {
	s.logger.Info("Shutting down server")

	// Stop the scanner
	if err := s.scanner.Stop(); err != nil {
		s.logger.Error("Failed to stop scanner", "error", err)
	}

	if err := s.httpServer.Shutdown(ctx); err != nil {
		return appErr.NewHandlerError(appErr.ErrInternal, "failed to shutdown server", err).
			WithOperation("ServerShutdown")
	}
	return nil
}
