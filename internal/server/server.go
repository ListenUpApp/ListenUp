package server

import (
	"context"
	"fmt"
	"github.com/ListenUpApp/ListenUp/internal/config"
	"github.com/ListenUpApp/ListenUp/internal/handler/api"
	"github.com/ListenUpApp/ListenUp/internal/handler/web"
	"github.com/ListenUpApp/ListenUp/internal/middleware"
	"github.com/ListenUpApp/ListenUp/internal/service"
	"github.com/gin-gonic/gin"
	"log/slog"
	"net/http"
)

type Server struct {
	config     *config.Config
	logger     *slog.Logger
	services   *service.Services
	router     *gin.Engine
	httpServer *http.Server
}

type Config struct {
	Config   *config.Config
	Logger   *slog.Logger
	Services *service.Services
}

func New(cfg Config) *Server {
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
	}
}

func (s *Server) Init(ctx context.Context) error {
	srv, err := s.services.Server.GetServer(ctx)
	if err != nil {
		return fmt.Errorf("checking server status: %w", err)
	}

	if srv == nil {
		if _, err := s.services.Server.CreateServer(ctx); err != nil {
			return fmt.Errorf("creating server: %w", err)
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
		// Public routes
		apiHandler.RegisterPublicRoutes(apiGroup)

		// Protected routes
		protected := apiGroup.Group("")
		// todo auth middleware goes here
		apiHandler.RegisterProtectedRoutes(protected)
	}

	// Web routes
	webHandler := web.NewHandler(web.Config{
		Services: s.services,
		Logger:   s.logger,
		Config:   s.config,
	})

	// Public web routes
	webPublicGroup := s.router.Group("")
	webHandler.RegisterPublicRoutes(webPublicGroup)

	protected := s.router.Group("")
	// todo auth middleware goes here
	webHandler.RegisterProtectedRoutes(protected)
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
