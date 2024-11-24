package web

import (
	"context"
	"log/slog"

	"github.com/ListenUpApp/ListenUp/internal/config"
	"github.com/ListenUpApp/ListenUp/internal/middleware"
	"github.com/ListenUpApp/ListenUp/internal/service"
	"github.com/ListenUpApp/ListenUp/internal/web/view/pages/library"
	"github.com/gin-gonic/gin"
)

type LibraryHandler struct {
	service *service.Services
	logger  *slog.Logger
	config  *config.Config
}

func NewLibraryHandler(config Config) *LibraryHandler {
	return &LibraryHandler{
		service: config.Services,
		logger:  config.Logger,
		config:  config.Config,
	}
}

func (h *LibraryHandler) RegisterRoutes(router *gin.RouterGroup) {
	router.GET("/", h.LibraryIndex)
}

func (h *LibraryHandler) LibraryIndex(c *gin.Context) {
	appCtx, exists := middleware.GetAppContext(c)
	if !exists {
		h.logger.Error("app context not found")
		c.String(500, "Error: app context not found")
		return
	}

	// Create a new context with the app context value
	ctx := context.WithValue(c.Request.Context(), "app_context", appCtx)

	if c.GetHeader("HX-Request") == "true" {
		err := library.LibraryIndexContent().Render(ctx, c.Writer)
		if err != nil {
			h.logger.Error("error rendering page",
				"error", err,
				"path", c.Request.URL.Path)
			c.String(500, "Error rendering page")
			return
		}
		return
	}
	err := library.LibraryIndexPage("Library").Render(ctx, c.Writer)
	if err != nil {
		h.logger.Error("error rendering page",
			"error", err,
			"path", c.Request.URL.Path)
		c.String(500, "Error rendering page")
		return
	}
}
