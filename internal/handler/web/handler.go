package web

import (
	"context"
	"log/slog"

	"github.com/ListenUpApp/ListenUp/internal/config"
	"github.com/ListenUpApp/ListenUp/internal/service"
	"github.com/ListenUpApp/ListenUp/internal/web/view/pages"
	"github.com/gin-gonic/gin"
)

type Handler struct {
	auth     *AuthHandler
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
}

func (h *Handler) HomePage(c *gin.Context) {
	// Get the app context
	appCtx, exists := c.Get("app_context")
	if !exists {
		h.logger.Error("no app_context found in gin context")
		c.String(500, "Server Error")
		return
	}

	// Create a new context with the app context
	ctx := context.WithValue(c.Request.Context(), "app_context", appCtx)

	page := pages.Home("Home")
	err := page.Render(ctx, c.Writer) // Use our modified context
	if err != nil {
		h.logger.Error("error rendering page",
			"error", err,
			"path", c.Request.URL.Path)
		c.String(500, "Error rendering page")
		return
	}
}
