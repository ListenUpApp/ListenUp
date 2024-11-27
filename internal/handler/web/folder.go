package web

import (
	"net/http"
	"path/filepath"

	appErr "github.com/ListenUpApp/ListenUp/internal/error"
	"github.com/ListenUpApp/ListenUp/internal/models"
	"github.com/ListenUpApp/ListenUp/internal/web/view/components"
	"github.com/gin-gonic/gin"
)

type FolderHandler struct {
	*BaseHandler
	formHandler *FormHandler
}

func NewFolderHandler(cfg Config, base *BaseHandler) *FolderHandler {

	formHandler := NewFormHandler()

	formHandler.AddErrorMessage(appErr.ErrNotFound, "Folder not found")
	formHandler.AddErrorMessage(appErr.ErrUnauthorized, "Unauthorized access to folder")
	formHandler.AddErrorMessage(appErr.ErrValidation, "Invalid folder path")

	return &FolderHandler{
		BaseHandler: base,
		formHandler: formHandler,
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
	isNavigation := c.GetHeader("HX-Target") == "folder-list"

	// For modal open (non-folder-list target), always use root
	if !isNavigation {
		path = "/"
	}

	folders, err := h.getFolderData(c, path)
	if err != nil {
		return
	}

	// For navigation within the modal
	if isNavigation {
		if err := h.RenderPublicComponent(c, components.FolderContent(folders)); err != nil {
			h.handleRenderError(c, err, "GetOSFolder.FolderContent")
		}
		return
	}

	// For initial modal render
	if err := h.RenderPublicComponent(c, components.FolderBrowser(folders)); err != nil {
		h.handleRenderError(c, err, "GetOSFolder.FolderBrowser")
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

	folders, err := h.services.Media.GetFolderStructure(c.Request.Context(), request)
	if err != nil {
		err = appErr.HandleServiceError(err, "getFolderData", map[string]interface{}{
			"path": path,
		})

		h.logger.ErrorContext(c.Request.Context(), "Failed to get folders",
			"path", path,
			"error", err)

		formData := h.formHandler.ProcessError(err, "getFolderData")
		h.RenderError(c, appErr.HTTPStatusCode(err), formData.Error)
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
		h.handleValidationError(c, "path", "No path provided")
		return
	}

	// Get the name as the last part of the path
	name := filepath.Base(path)

	// Validate the path exists
	request := models.GetFolderRequest{
		Path:  path,
		Depth: 0,
	}

	_, err := h.services.Media.GetFolderStructure(c.Request.Context(), request)
	if err != nil {
		h.logger.ErrorContext(c.Request.Context(), "Failed to validate folder path",
			"path", path,
			"error", err)

		formData := h.formHandler.ProcessError(err, "SelectOSFolder")
		h.RenderError(c, appErr.HTTPStatusCode(err), formData.Error)
		return
	}

	if err := h.RenderPublicComponent(c, components.SelectedFolder(name)); err != nil {
		h.handleRenderError(c, err, "SelectOSFolder.SelectedFolder")
	}
}

func (h *FolderHandler) CreateFolder(c *gin.Context) {
	path := c.PostForm("path")
	name := c.PostForm("name")

	if path == "" || name == "" {
		h.handleValidationError(c, "folder", "Both path and name are required")
		return
	}

	h.logger.InfoContext(c.Request.Context(), "Creating Folder",
		"name", name,
		"path", path)

	// TODO: Implement folder creation logic
}

func (h *FolderHandler) handleRenderError(c *gin.Context, err error, operation string) {
	h.logger.ErrorContext(c.Request.Context(), "Render error",
		"error", err,
		"operation", operation)

	h.RenderError(c, http.StatusInternalServerError, "Failed to render page")
}
