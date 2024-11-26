package web

import (
	"errors"
	"net/http"

	errorhandling "github.com/ListenUpApp/ListenUp/internal/error_handling"
	"github.com/ListenUpApp/ListenUp/internal/models"
	"github.com/ListenUpApp/ListenUp/internal/util"
	"github.com/ListenUpApp/ListenUp/internal/web/view/forms"
	auth "github.com/ListenUpApp/ListenUp/internal/web/view/pages/auth"
	"github.com/gin-gonic/gin"
)

type AuthHandler struct {
	*BaseHandler
}

func NewAuthHandler(cfg Config, base *BaseHandler) *AuthHandler {
	return &AuthHandler{
		BaseHandler: base,
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
	isSetup, err := h.services.Auth.IsServerSetup(c.Request.Context())
	if err != nil {
		h.RenderError(c, http.StatusInternalServerError, "Failed to check server setup")
		return
	}

	if isSetup {
		h.HTMXRedirect(c, "/auth/login")
		return
	}

	data := forms.RegisterData{
		RootUser: !isSetup,
		Error:    "",
		Fields:   make(map[string]string),
	}

	h.RenderPublicComponent(c, auth.Register(data))
}

func (h *AuthHandler) loginPageHandler(c *gin.Context) {
	isSetup, err := h.services.Auth.IsServerSetup(c.Request.Context())
	if err != nil {
		h.RenderError(c, http.StatusInternalServerError, "Failed to check server setup")
		return
	}

	if !isSetup {
		h.HTMXRedirect(c, "/auth/register")
		return
	}

	data := forms.LoginData{
		Error:  "",
		Fields: make(map[string]string),
	}

	h.RenderPublicComponent(c, auth.Login(data))
}

func (h *AuthHandler) Register(c *gin.Context) {
	var req models.RegisterRequest
	if err := c.ShouldBind(&req); err != nil {
		h.logger.Debug("json binding error", "error", err)
		h.RenderPublicComponent(c, forms.RegisterForm(forms.RegisterData{
			RootUser: false,
			Error:    "Invalid request format",
			Fields:   make(map[string]string),
		}))
		return
	}

	// Validate the request
	if errors := h.Validate(req); len(errors) > 0 {
		h.RenderPublicComponent(c, forms.RegisterForm(forms.RegisterData{
			RootUser: false,
			Error:    "Please check the form for errors",
			Fields:   errors,
		}))
		return
	}

	if err := h.services.Auth.RegisterUser(c.Request.Context(), req); err != nil {
		data := h.handleRegisterError(err)
		h.RenderPublicComponent(c, forms.RegisterForm(data))
		return
	}

	h.HTMXRedirect(c, "/auth/login")
}

func (h *AuthHandler) Login(c *gin.Context) {
	var req models.LoginRequest
	if err := c.ShouldBind(&req); err != nil {
		h.RenderPublicComponent(c, forms.LoginForm(forms.LoginData{
			Error:  "Invalid request format",
			Fields: make(map[string]string),
		}))
		return
	}

	// Validate the request
	if errors := h.Validate(req); len(errors) > 0 {
		h.RenderPublicComponent(c, forms.LoginForm(forms.LoginData{
			Error:  "Please check the form for errors",
			Fields: errors,
		}))
		return
	}

	response, err := h.services.Auth.LoginUser(c.Request.Context(), req)
	if err != nil {
		data := h.handleLoginError(err)
		h.RenderPublicComponent(c, forms.LoginForm(data))
		return
	}

	util.SetAuthCookie(c, response.Token)
	h.HTMXRedirect(c, "/")
}

func (h *AuthHandler) Logout(c *gin.Context) {
	util.ClearAuthCookie(c)
	h.HTMXRedirect(c, "/auth/login")
}

func (h *AuthHandler) handleLoginError(err error) forms.LoginData {
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

	return data
}

func (h *AuthHandler) handleRegisterError(err error) forms.RegisterData {
	data := forms.RegisterData{
		RootUser: false,
		Error:    h.getRegisterErrorMessage(err),
		Fields:   make(map[string]string),
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

	return data
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
