package web

import (
	"log/slog"

	"github.com/ListenUpApp/ListenUp/internal/config"
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
	err := RenderPage(c, RenderConfig{
		Writer:    c.Writer,
		Logger:    h.logger,
		Path:      c.Request.URL.Path,
		PageTitle: "Library",
		IsHTMX:    c.GetHeader("HX-Request") == "true",
	},
		library.LibraryIndexPage("Library"),
		library.LibraryIndexContent())

	if err != nil {
		return
	}
}
