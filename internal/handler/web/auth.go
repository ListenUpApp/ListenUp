package web

import (
	"net/http"

	appErr "github.com/ListenUpApp/ListenUp/internal/error"
	"github.com/ListenUpApp/ListenUp/internal/models"
	"github.com/ListenUpApp/ListenUp/internal/util"
	"github.com/ListenUpApp/ListenUp/internal/web/view/forms"
	auth "github.com/ListenUpApp/ListenUp/internal/web/view/pages/auth"
	"github.com/gin-gonic/gin"
)

type AuthHandler struct {
	*BaseHandler
	formHandler *FormHandler
}

func NewAuthHandler(cfg Config, base *BaseHandler) *AuthHandler {
	formHandler := NewFormHandler()

	// Add auth-specific error messages
	formHandler.AddErrorMessage(appErr.ErrUnauthorized, "Invalid email or password")
	formHandler.AddErrorMessage(appErr.ErrConflict, "A user with this email already exists")

	return &AuthHandler{
		BaseHandler: base,
		formHandler: formHandler,
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
		h.renderRegisterForm(c, FormData{
			Error: "Invalid request format",
		})
		return
	}

	// Validate the request
	if errors := h.Validate(req); len(errors) > 0 {
		h.renderRegisterForm(c, FormData{
			Error:  "Please check the form for errors",
			Fields: errors,
		})
		return
	}

	if err := h.services.Auth.RegisterUser(c.Request.Context(), req); err != nil {
		h.renderRegisterForm(c, h.formHandler.ProcessError(err, "Register"))
		return
	}

	h.HTMXRedirect(c, "/auth/login")
}

func (h *AuthHandler) Login(c *gin.Context) {
	var req models.LoginRequest
	if err := c.ShouldBind(&req); err != nil {
		h.renderLoginForm(c, FormData{
			Error: "Invalid request format",
		})
		return
	}

	if errors := h.Validate(req); len(errors) > 0 {
		h.renderLoginForm(c, FormData{
			Error:  "Please check the form for errors",
			Fields: errors,
		})
		return
	}

	response, err := h.services.Auth.LoginUser(c.Request.Context(), req)
	if err != nil {
		h.renderLoginForm(c, h.formHandler.ProcessError(err, "Login"))
		return
	}

	util.SetAuthCookie(c, response.Token)
	h.HTMXRedirect(c, "/")
}

// Helper methods for rendering forms
func (h *AuthHandler) renderRegisterForm(c *gin.Context, data FormData) {
	h.RenderPublicComponent(c, forms.RegisterForm(forms.RegisterData{
		RootUser: false,
		Error:    data.Error,
		Fields:   data.Fields,
	}))
}

func (h *AuthHandler) renderLoginForm(c *gin.Context, data FormData) {
	h.RenderPublicComponent(c, forms.LoginForm(forms.LoginData{
		Error:  data.Error,
		Fields: data.Fields,
	}))
}

func (h *AuthHandler) Logout(c *gin.Context) {
	util.ClearAuthCookie(c)
	h.HTMXRedirect(c, "/auth/login")
}
