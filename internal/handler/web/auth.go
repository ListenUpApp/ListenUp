package web

import (
	"errors"
	"fmt"
	"github.com/ListenUpApp/ListenUp/internal/config"
	errorhandling "github.com/ListenUpApp/ListenUp/internal/error_handling"
	"github.com/ListenUpApp/ListenUp/internal/models"
	"github.com/ListenUpApp/ListenUp/internal/service"
	"github.com/ListenUpApp/ListenUp/internal/web/view/forms"
	"github.com/ListenUpApp/ListenUp/internal/web/view/pages/auth"
	"github.com/gin-gonic/gin"
	"log/slog"
)

type AuthHandler struct {
	service *service.AuthService
	logger  *slog.Logger
	config  *config.Config
}

func NewAuthHandler(config Config) *AuthHandler {
	return &AuthHandler{
		service: config.Services.Auth,
		logger:  config.Logger,
		config:  config.Config,
	}
}

func (h *AuthHandler) RegisterRoutes(router *gin.RouterGroup) {
	router.GET("/register", h.RegisterPage)
	router.GET("/login", h.LoginPage)
	router.POST("/register", h.Register)
}

func (h *AuthHandler) LoginPage(c *gin.Context) {
	data := forms.LoginData{
		Error:  "",
		Fields: make(map[string]string),
	}
	page := auth.Login(data)
	err := page.Render(c.Request.Context(), c.Writer)
	if err != nil {
		fmt.Errorf("Error Rendering Page")
	}
}

func (h *AuthHandler) RegisterPage(c *gin.Context) {
	data := forms.RegisterData{
		RootUser: false,
		Error:    "",
		Fields:   make(map[string]string),
	}

	page := auth.Register(data)
	err := page.Render(c.Request.Context(), c.Writer)
	if err != nil {
		fmt.Errorf("Error Rendering Page")
	}
}

func (h *AuthHandler) Register(c *gin.Context) {
	var req models.RegisterRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		data := forms.RegisterData{
			RootUser: false,
			Error:    "Invalid request format",
			Fields:   make(map[string]string),
		}
		forms.RegisterForm(data).Render(c.Request.Context(), c.Writer)
		return
	}

	if err := h.service.RegisterUser(c.Request.Context(), req); err != nil {
		data := forms.RegisterData{
			RootUser: false,
			Error:    h.getErrorMessage(err),
			Fields:   make(map[string]string),
		}

		var appErr *errorhandling.AppError
		if errors.As(err, &appErr) {
			switch appErr.Type {
			case errorhandling.ErrorTypeValidation:
				// Use the validation errors returned from service
				data.Fields = appErr.Data.(map[string]string)
			case errorhandling.ErrorTypeConflict:
				data.Fields["email"] = appErr.Message
			}
		}

		forms.RegisterForm(data).Render(c.Request.Context(), c.Writer)
		return
	}

	c.Header("HX-Redirect", "/auth/login")
}

func (h *AuthHandler) getErrorMessage(err error) string {
	var appErr *errorhandling.AppError
	if errors.As(err, &appErr) {
		switch appErr.Type {
		case errorhandling.ErrorTypeValidation:
			return "Please check the form for errors"
		case errorhandling.ErrorTypeConflict:
			return "A user with this email already exists"
		case errorhandling.ErrorTypeInternal:
			return "An unexpected error occurred. Please try again"
		}
	}
	return "An unexpected error occurred. Please try again"
}
