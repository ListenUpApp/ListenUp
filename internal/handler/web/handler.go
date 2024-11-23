package web

import (
	"fmt"
	"github.com/ListenUpApp/ListenUp/internal/config"
	"github.com/ListenUpApp/ListenUp/internal/service"
	"github.com/ListenUpApp/ListenUp/internal/web/view/pages"
	"github.com/gin-gonic/gin"
	"log/slog"
)

type Handler struct {
	auth   *AuthHandler
	logger *slog.Logger
	config *config.Config
}

type Config struct {
	Services *service.Services
	Logger   *slog.Logger
	Config   *config.Config
}

func NewHandler(cfg Config) *Handler {
	return &Handler{
		auth:   NewAuthHandler(cfg),
		logger: cfg.Logger,
		config: cfg.Config,
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
	page := pages.Home()
	err := page.Render(c.Request.Context(), c.Writer)
	if err != nil {
		fmt.Errorf("Error Rendering Page")
	}
}
