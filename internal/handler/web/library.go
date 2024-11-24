package web

import (
	"github.com/ListenUpApp/ListenUp/internal/web/view/pages/library"
	"github.com/gin-gonic/gin"
)

type LibraryHandler struct {
	*BaseHandler
}

func NewLibraryHandler(cfg Config, handler *BaseHandler) *LibraryHandler {
	return &LibraryHandler{
		BaseHandler: handler,
	}
}

func (h *LibraryHandler) RegisterRoutes(router *gin.RouterGroup) {
	router.GET("/", h.LibraryIndex)
	router.GET("/new", h.LibraryCreate)
}

func (h *LibraryHandler) LibraryIndex(c *gin.Context) {
	err := h.RenderPage(c, "Library",
		library.LibraryIndexPage("Library"),
		library.LibraryIndexContent())

	if err != nil {
		return
	}
}

func (h *LibraryHandler) LibraryCreate(c *gin.Context) {
	err := h.RenderPage(c, "Create Library",
		library.CreateLibraryPage("Create New Library"),
		library.CreateLibraryContent())

	if err != nil {
		return
	}
}
