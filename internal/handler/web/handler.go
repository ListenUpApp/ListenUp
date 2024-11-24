package web

import (
	"context"
	"log/slog"

	"github.com/ListenUpApp/ListenUp/internal/config"
	"github.com/ListenUpApp/ListenUp/internal/middleware"
	"github.com/ListenUpApp/ListenUp/internal/service"
	"github.com/ListenUpApp/ListenUp/internal/web/view/pages"
	"github.com/gin-gonic/gin"
)

type Handler struct {
	auth     *AuthHandler
	library  *LibraryHandler
	services *service.Services
	logger   *slog.Logger
	config   *config.Config
}

type Config struct {
	Services *service.Services
	Logger   *slog.Logger
	Config   *config.Config
}

func NewHandler(cfg Config) *Handler {
	return &Handler{
		auth:     NewAuthHandler(cfg),
		library:  NewLibraryHandler(cfg),
		services: cfg.Services,
		logger:   cfg.Logger,
		config:   cfg.Config,
	}
}

func (h *Handler) RegisterPublicRoutes(r *gin.RouterGroup) {
	h.logger.Info("registering public routes")
	auth := r.Group("/auth")
	{
		h.auth.RegisterRoutes(auth)
	}
}

func (h *Handler) RegisterProtectedRoutes(r *gin.RouterGroup) {
	r.GET("/", h.HomePage)
	library := r.Group("/library")
	{
		h.library.RegisterRoutes(library)
	}
}

func (h *Handler) HomePage(c *gin.Context) {
	appCtx, exists := middleware.GetAppContext(c)
	if !exists {
		h.logger.Error("app context not found")
		c.String(500, "Error: app context not found")
		return
	}

	// Create a new context with the app context value
	ctx := context.WithValue(c.Request.Context(), "app_context", appCtx)

	if c.GetHeader("HX-Request") == "true" {
		err := pages.HomeContent().Render(ctx, c.Writer)
		if err != nil {
			h.logger.Error("error rendering page",
				"error", err,
				"path", c.Request.URL.Path)
			c.String(500, "Error rendering page")
			return
		}
		return
	}

	// For regular requests, return the full page
	err := pages.HomePage("Home").Render(ctx, c.Writer)
	if err != nil {
		h.logger.Error("error rendering page",
			"error", err,
			"path", c.Request.URL.Path)
		c.String(500, "Error rendering page")
		return
	}
}
