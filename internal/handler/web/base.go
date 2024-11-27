package web

import (
	"context"
	"fmt"
	"net/http"
	"strings"

	"github.com/ListenUpApp/ListenUp/internal/config"
	appcontext "github.com/ListenUpApp/ListenUp/internal/context"
	logging "github.com/ListenUpApp/ListenUp/internal/logger"
	"github.com/ListenUpApp/ListenUp/internal/service"
	"github.com/a-h/templ"
	"github.com/gin-gonic/gin"
	"github.com/go-playground/validator/v10"
)

type BaseHandler struct {
	logger    *logging.AppLogger
	services  *service.Services
	config    *config.Config
	validator *validator.Validate
}

// RenderError handles error responses
func (h *BaseHandler) RenderError(c *gin.Context, status int, message string) {
	h.logger.Error(message,
		"path", c.Request.URL.Path,
		"status", status)

	if c.GetHeader("HX-Request") == "true" {
		c.Header("HX-Reswap", "none")
		c.String(status, message)
		return
	}

	c.String(status, message)
}

// HTMXRedirect handles HTMX-aware redirects
func (h *BaseHandler) HTMXRedirect(c *gin.Context, path string) {
	c.Header("HX-Redirect", path)
	if c.Request.Method == http.MethodGet {
		c.Header("Location", path)
		c.Status(http.StatusTemporaryRedirect)
	} else {
		c.Status(http.StatusOK)
	}
}

// renderWithContext renders a component with the appropriate context
func (h *BaseHandler) renderWithContext(c *gin.Context, component templ.Component, requireAppContext bool) error {
	var ctx context.Context = c.Request.Context()

	if requireAppContext {
		appCtx, exists := appcontext.GetAppContextFromGin(c)
		if !exists {
			return fmt.Errorf("app context not found")
		}
		ctx = appcontext.WithAppContext(ctx, appCtx)
	}

	if err := component.Render(ctx, c.Writer); err != nil {
		h.logger.Error("error rendering component",
			"error", err,
			"path", c.Request.URL.Path)
		return err
	}
	return nil
}

// RenderPublicComponent renders a component without requiring an app context
func (h *BaseHandler) RenderPublicComponent(c *gin.Context, component templ.Component) error {
	if err := h.renderWithContext(c, component, false); err != nil {
		h.RenderError(c, http.StatusInternalServerError, "Error rendering component")
		return err
	}
	return nil
}

// RenderComponent renders a component that requires an app context
func (h *BaseHandler) RenderComponent(c *gin.Context, component templ.Component) error {
	if err := h.renderWithContext(c, component, true); err != nil {
		h.RenderError(c, http.StatusInternalServerError, "Error rendering component")
		return err
	}
	return nil
}

// RenderPage handles full page rendering with HTMX support
func (h *BaseHandler) RenderPage(c *gin.Context, pageTitle string, fullPage, partial templ.Component) error {
	component := fullPage
	if c.GetHeader("HX-Request") == "true" {
		component = partial
		// Get the current page from the request header or use the provided title
		currentPage := c.GetHeader("X-Current-Page")
		if currentPage != "" {
			pageTitle = currentPage
		}
		c.Header("Content-Type", "text/html")
		c.Header("HX-Push-Url", c.Request.URL.Path)
		c.Header("HX-Replace-Url", "true")
		c.Header("HX-Title", pageTitle)
		c.Header("X-Page-Title", "ListenUp | "+pageTitle)
	}

	return h.RenderComponent(c, component)
}

// Validate handles struct validation with custom messages
func (h *BaseHandler) Validate(data interface{}) map[string]string {
	errors := make(map[string]string)

	err := h.validator.Struct(data)
	if err != nil {
		// Get custom messages if available
		var messages map[string]string
		if v, ok := data.(interface{ Messages() map[string]string }); ok {
			messages = v.Messages()
		}

		for _, err := range err.(validator.ValidationErrors) {
			field := strings.ToLower(err.Field()[:1]) + err.Field()[1:] // convert Email to email

			// Check for custom message
			if msg, ok := messages[err.Field()+"."+err.Tag()]; ok {
				errors[field] = msg
				continue
			}

			// Default messages
			switch err.Tag() {
			case "required":
				errors[field] = "This field is required"
			case "email":
				errors[field] = "Please enter a valid email address"
			case "min":
				errors[field] = fmt.Sprintf("Must be at least %s characters", err.Param())
			case "eqfield":
				errors[field] = fmt.Sprintf("Must match %s", strings.ToLower(err.Param()[:1])+err.Param()[1:])
			default:
				errors[field] = fmt.Sprintf("Invalid value for %s", field)
			}
		}
	}

	return errors
}
