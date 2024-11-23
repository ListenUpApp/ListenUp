package web

import (
	"errors"
	"github.com/ListenUpApp/ListenUp/internal/config"
	errorhandling "github.com/ListenUpApp/ListenUp/internal/error_handling"
	"github.com/ListenUpApp/ListenUp/internal/models"
	"github.com/ListenUpApp/ListenUp/internal/service"
	"github.com/ListenUpApp/ListenUp/internal/util"
	"github.com/ListenUpApp/ListenUp/internal/web/view/forms"
	auth "github.com/ListenUpApp/ListenUp/internal/web/view/pages/auth"
	"github.com/gin-gonic/gin"
	"log/slog"
	"net/http"
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
	router.GET("/register", h.registerPageHandler)
	router.GET("/login", h.loginPageHandler)
	router.POST("/register", h.Register)
	router.POST("/login", h.Login)
	router.POST("/logout", h.Logout)
}

func (h *AuthHandler) registerPageHandler(c *gin.Context) {
	isSetup, err := h.service.IsServerSetup(c.Request.Context())
	if err != nil {
		h.logger.Error("failed to check server setup", "error", err)
		c.Status(http.StatusInternalServerError)
		return
	}

	if isSetup {
		h.logger.Info("server already setup, redirecting to login")
		c.Header("HX-Redirect", "/auth/login")
		c.Header("Location", "/auth/register")
		c.Status(http.StatusTemporaryRedirect) // Add this
		c.Abort()
		return
	}

	data := forms.RegisterData{
		RootUser: !isSetup,
		Error:    "",
		Fields:   make(map[string]string),
	}

	page := auth.Register(data)
	err = page.Render(c.Request.Context(), c.Writer)
	if err != nil {
		h.logger.Error("failed to render register page", "error", err)
		c.Status(http.StatusInternalServerError)
		return
	}
}

func (h *AuthHandler) loginPageHandler(c *gin.Context) {
	isSetup, err := h.service.IsServerSetup(c.Request.Context())
	if err != nil {
		h.logger.Error("failed to check server setup", "error", err)
		c.Status(http.StatusInternalServerError)
		return
	}

	if !isSetup {
		h.logger.Info("server not setup, redirecting to register")
		c.Header("HX-Redirect", "/auth/register")
		c.Header("Location", "/auth/register")
		c.Status(http.StatusTemporaryRedirect)
		c.Abort()
		return
	}

	data := forms.LoginData{
		Error:  "",
		Fields: make(map[string]string),
	}

	page := auth.Login(data)
	err = page.Render(c.Request.Context(), c.Writer)
	if err != nil {
		h.logger.Error("failed to render login page", "error", err)
		c.Status(http.StatusInternalServerError)
		return
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
			Error:    h.getRegisterErrorMessage(err),
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

func (h *AuthHandler) Login(c *gin.Context) {
	var req models.LoginRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		data := forms.LoginData{
			Error:  "Invalid request format",
			Fields: make(map[string]string),
		}
		forms.LoginForm(data).Render(c.Request.Context(), c.Writer)
		return
	}

	response, err := h.service.LoginUser(c.Request.Context(), req)
	if err != nil {
		data := forms.LoginData{
			Error:  h.getLoginErrorMessage(err),
			Fields: make(map[string]string),
		}

		var appErr *errorhandling.AppError
		if errors.As(err, &appErr) {
			switch appErr.Type {
			case errorhandling.ErrorTypeValidation:
				data.Fields = appErr.Data.(map[string]string)
			case errorhandling.ErrorTypeConflict:
				data.Fields["email"] = appErr.Message
			}
		}

		forms.LoginForm(data).Render(c.Request.Context(), c.Writer)
		return
	}

	// Set auth cookie
	util.SetAuthCookie(c, response.Token)
	c.Header("HX-Redirect", "/")
}

func (h *AuthHandler) Logout(c *gin.Context) {
	// Clear the auth cookie
	util.ClearAuthCookie(c)

	// Redirect to login page
	c.Header("HX-Redirect", "/auth/login")
}

func (h *AuthHandler) getLoginErrorMessage(err error) string {
	var appErr *errorhandling.AppError
	if errors.As(err, &appErr) {
		switch appErr.Type {
		case errorhandling.ErrorTypeValidation:
			return "Please check the form for errors"
		case errorhandling.ErrorTypeUnauthorized, errorhandling.ErrorTypeNotFound:
			return "Invalid email or password"
		case errorhandling.ErrorTypeInternal:
			return "An unexpected error occurred. Please try again"
		}
	}
	return "An unexpected error occurred. Please try again"
}

func (h *AuthHandler) getRegisterErrorMessage(err error) string {
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
