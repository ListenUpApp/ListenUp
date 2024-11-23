package web

import (
	"fmt"
	"log/slog"

	"github.com/ListenUpApp/ListenUp/internal/config"
	"github.com/ListenUpApp/ListenUp/internal/service"
	"github.com/ListenUpApp/ListenUp/internal/util"
	"github.com/ListenUpApp/ListenUp/internal/web/view/layouts"
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
	claims, err := util.GetCustomClaims(c)
	if err != nil {
		fmt.Errorf("Error getting claims")
	}
	user, err := h.services.User.GetUserByID(c, claims.UserID)
	if err != nil {
		fmt.Errorf("Error getting user")
	}
	data := layouts.AppData{
		PageName: "Home",
		User:     *user,
	}
	page := pages.Home(data)
	err = page.Render(c.Request.Context(), c.Writer)
	if err != nil {
		fmt.Errorf("Error Rendering Page")
	}
}
