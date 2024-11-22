package api

import (
	"github.com/ListenUpApp/ListenUp/internal/config"
	"github.com/ListenUpApp/ListenUp/internal/service"
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

func (h *Handler) RegisterPublicRoutes(router *gin.RouterGroup) {
	auth := router.Group("/auth")
	{
		h.auth.RegisterRoutes(auth)
	}
}

func (h *Handler) RegisterProtectedRoutes(router *gin.RouterGroup) {
}
