package api

import (
	"github.com/ListenUpApp/ListenUp/internal/config"
	errorhandling "github.com/ListenUpApp/ListenUp/internal/error_handling"
	"github.com/ListenUpApp/ListenUp/internal/models"
	"github.com/ListenUpApp/ListenUp/internal/service"
	"github.com/gin-gonic/gin"
	"log/slog"
	"net/http"
)

type AuthHandler struct {
	service *service.AuthService
	logger  *slog.Logger
	config  *config.Config
}

func NewAuthHandler(cfg Config) *AuthHandler {
	return &AuthHandler{
		service: cfg.Services.Auth,
		logger:  cfg.Logger,
		config:  cfg.Config,
	}
}

func (h *AuthHandler) RegisterRoutes(router *gin.RouterGroup) {
	router.POST("/register", h.Register)
	router.GET("/ping", h.Ping)
}

func (h *AuthHandler) Ping(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{
		"message": "pong",
	})
}

func (h *AuthHandler) Register(c *gin.Context) {
	var createUserReq models.RegisterRequest

	if err := c.ShouldBindJSON(&createUserReq); err != nil {
		c.Error(errorhandling.NewValidationError("invalid request payload"))
		return
	}

	if err := h.service.RegisterUser(c, createUserReq); err != nil {
		c.Error(err)
		return
	}

	c.JSON(http.StatusCreated, gin.H{
		"success": true,
		"data": gin.H{
			"message": "user registered successfully",
		},
	})
}