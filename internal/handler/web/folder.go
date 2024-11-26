package web

import (
	"fmt"
	"net/http"
	"path/filepath"

	errorhandling "github.com/ListenUpApp/ListenUp/internal/error_handling"
	"github.com/ListenUpApp/ListenUp/internal/models"
	"github.com/ListenUpApp/ListenUp/internal/web/view/components"
	"github.com/gin-gonic/gin"
)

type FolderHandler struct {
	*BaseHandler
}

func NewFolderHandler(cfg Config, handler *BaseHandler) *FolderHandler {
	return &FolderHandler{
		BaseHandler: handler,
	}
}

func (h *FolderHandler) RegisterRoutes(router *gin.RouterGroup) {
	osGroup := router.Group("/os")
	{
		osGroup.GET("", h.GetOSFolder)
		osGroup.POST("/select", h.SelectOSFolder)
	}
	router.POST("/", h.CreateFolder)
}

func (h *FolderHandler) GetOSFolder(c *gin.Context) {
	path := c.Query("path")

	// For modal open (non-folder-list target), always use root
	if c.GetHeader("HX-Target") != "folder-list" {
		path = "/"
	}

	folders, err := h.getFolderData(c, path)
	if err != nil {
		return
	}

	// For navigation within the modal
	if c.GetHeader("HX-Target") == "folder-list" {
		if err := h.RenderPublicComponent(c, components.FolderContent(folders)); err != nil {
			h.RenderError(c, http.StatusInternalServerError, "Failed to render folder content")
		}
		return
	}

	// For initial modal render
	if err := h.RenderPublicComponent(c, components.FolderBrowser(folders)); err != nil {
		h.RenderError(c, http.StatusInternalServerError, "Failed to render folder browser")
	}
}

func (h *FolderHandler) getFolderData(c *gin.Context, path string) (*models.GetFolderResponse, error) {
	h.logger.InfoContext(c.Request.Context(), "Getting folders",
		"path", path,
		"is_htmx", c.GetHeader("HX-Request") == "true")

	// Ensure the path is absolute
	if !filepath.IsAbs(path) {
		path = filepath.Join("/", path)
	}

	request := models.GetFolderRequest{
		Path:  path,
		Depth: 1,
	}

	folders, err := h.services.Folder.GetFolderStructure(c.Request.Context(), request)
	if err != nil {
		h.logger.ErrorContext(c.Request.Context(), "Failed to get folders",
			"path", path,
			"error", err)

		if appErr, ok := err.(*errorhandling.AppError); ok {
			switch appErr.Type {
			case errorhandling.ErrorTypeNotFound:
				h.RenderError(c, http.StatusNotFound, "Folder not found")
			case errorhandling.ErrorTypeValidation:
				h.RenderError(c, http.StatusBadRequest, appErr.Message)
			case errorhandling.ErrorTypeUnauthorized:
				h.RenderError(c, http.StatusUnauthorized, "Unvauthorized access to folder")
			default:
				h.RenderError(c, http.StatusInternalServerError, "Failed to access folder")
			}
			return nil, err
		}

		h.RenderError(c, http.StatusInternalServerError, "Failed to get folders")
		return nil, err
	}

	// Create a response object even if no folders were found
	if folders == nil {
		folders = &models.GetFolderResponse{
			Name:       filepath.Base(path),
			Path:       path,
			Depth:      0,
			SubFolders: []models.GetFolderResponse{},
		}
	}

	return folders, nil
}

func (h *FolderHandler) SelectOSFolder(c *gin.Context) {
	path := c.Query("path")
	if path == "" {
		h.RenderError(c, http.StatusBadRequest, "No path provided")
		return
	}

	// Get the name as the last part of the path
	name := filepath.Base(path)

	// Validate the path exists
	request := models.GetFolderRequest{
		Path:  path,
		Depth: 0,
	}

	_, err := h.services.Folder.GetFolderStructure(c.Request.Context(), request)
	if err != nil {
		h.logger.ErrorContext(c.Request.Context(), "Failed to validate folder path",
			"path", path,
			"error", err)
		h.RenderError(c, http.StatusBadRequest, "Invalid folder path")
		return
	}

	// Now pass both name and path to the SelectedFolder component
	if err := h.RenderPublicComponent(c, components.SelectedFolder(name)); err != nil {
		h.RenderError(c, http.StatusInternalServerError, "Failed to render selected folder")
		return
	}
}

func (h *FolderHandler) CreateFolder(c *gin.Context) {
	fmt.Println("Create Route")
	path := c.PostForm("path")
	name := c.PostForm("name")
	h.logger.InfoContext(c.Request.Context(), "Creating Folder", "Name", name, "path", path)
}
