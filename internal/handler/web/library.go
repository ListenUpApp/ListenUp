package web

import (
	"fmt"
	"strings"

	"github.com/ListenUpApp/ListenUp/internal/middleware"
	"github.com/ListenUpApp/ListenUp/internal/models"
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
	router.GET("/new", h.CreateLibraryPage)
	router.POST("/new", h.CreateLibrary)
}

func (h *LibraryHandler) LibraryIndex(c *gin.Context) {
	err := h.RenderPage(c, "Library",
		library.LibraryIndexPage(),
		library.LibraryIndexContent())

	if err != nil {
		return
	}
}

func (h *LibraryHandler) CreateLibraryPage(c *gin.Context) {
	err := h.RenderPage(c, "Create Library",
		library.CreateLibraryPage(),
		library.CreateLibraryContent())

	if err != nil {
		return
	}
}

func (h *LibraryHandler) CreateLibrary(c *gin.Context) {
	// Parse form manually since we have nested data
	if err := c.Request.ParseForm(); err != nil {
		h.logger.ErrorContext(c.Request.Context(), "Failed to parse form", "error", err)
		h.HTMXRedirect(c, "/library")
		return
	}

	// Create request object
	req := models.CreateLibraryRequest{
		Name:    c.PostForm("name"),
		Folders: make([]models.CreateFolderRequest, 0),
	}

	// Get form values
	formValues := c.Request.PostForm

	// Count how many folders we have by looking for folder.X.name entries
	folderCount := 0
	for key := range formValues {
		if strings.HasPrefix(key, "folders.") && strings.HasSuffix(key, ".name") {
			folderCount++
		}
	}

	// Parse folders
	for i := 0; i < folderCount; i++ {
		nameKey := fmt.Sprintf("folders.%d.name", i)
		pathKey := fmt.Sprintf("folders.%d.path", i)

		name := formValues.Get(nameKey)
		path := formValues.Get(pathKey)

		if name != "" && path != "" {
			req.Folders = append(req.Folders, models.CreateFolderRequest{
				Name: name,
				Path: path,
			})
		}
	}

	// Log the parsed request
	h.logger.InfoContext(c.Request.Context(), "Parsed request",
		"name", req.Name,
		"folderCount", len(req.Folders),
		"folders", req.Folders)

	// Get app context
	appCtx, _ := middleware.GetAppContext(c)

	// Create library
	_, err := h.services.Library.CreateLibrary(c.Request.Context(), appCtx.User.ID, req)
	if err != nil {
		h.logger.ErrorContext(c.Request.Context(), "Failed to create library",
			"userId", appCtx.User.ID,
			"error", err,
			"requestData", req)
		h.HTMXRedirect(c, "/library")
		return
	}

	h.HTMXRedirect(c, "/library")
}
