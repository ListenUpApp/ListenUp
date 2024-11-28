package web

import (
	"fmt"
	"strconv"
	"strings"

	appErr "github.com/ListenUpApp/ListenUp/internal/error"
	"github.com/ListenUpApp/ListenUp/internal/middleware"
	"github.com/ListenUpApp/ListenUp/internal/models"
	"github.com/ListenUpApp/ListenUp/internal/web/view/pages/library"
	"github.com/gin-gonic/gin"
)

type LibraryHandler struct {
	*BaseHandler
	formHandler *FormHandler
}

func NewLibraryHandler(cfg Config, base *BaseHandler) *LibraryHandler {
	formHandler := NewFormHandler()
	// Add library-specific error messages
	formHandler.AddErrorMessage(appErr.ErrConflict, "This library name is already taken")

	return &LibraryHandler{
		BaseHandler: base,
		formHandler: formHandler,
	}
}

func (h *LibraryHandler) RegisterRoutes(router *gin.RouterGroup) {
	router.GET("/", h.LibraryIndex)
	router.GET("/new", h.CreateLibraryPage)
	router.POST("/new", h.CreateLibrary)
}

func (h *LibraryHandler) LibraryIndex(c *gin.Context) {
	appCtx, err := middleware.GetAppContext(c)
	if err != nil {
		h.handleRenderError(c, err, "LibraryIndex")
		return
	}

	// Get page parameters
	page, pageSize := h.getPaginationParams(c)

	var books *models.BookList
	if appCtx.ActiveLibrary != nil {
		books, err = h.services.Media.GetBooks(c.Request.Context(), appCtx.ActiveLibrary.ID, page, pageSize)
		if err != nil {
			h.logger.ErrorContext(c.Request.Context(), "Failed to get books",
				"library_id", appCtx.ActiveLibrary.ID,
				"error", err)
			books = &models.BookList{
				Books: []models.ListAudiobook{},
				Pagination: models.Pagination{
					CurrentPage: 1,
					PageSize:    pageSize,
					TotalItems:  0,
					TotalPages:  0,
				},
			}
		}
	}

	if err := h.RenderPage(c, "Library",
		library.LibraryIndexPage(books.Books, books.Pagination),
		library.LibraryIndexContent(books.Books, books.Pagination)); err != nil {
		h.handleRenderError(c, err, "LibraryIndex")
	}
}

func (h *LibraryHandler) getPaginationParams(c *gin.Context) (page, pageSize int) {
	// Parse page number
	pageStr := c.DefaultQuery("page", "1")
	page, err := strconv.Atoi(pageStr)
	if err != nil || page < 1 {
		page = 1
	}

	// Parse page size
	pageSizeStr := c.DefaultQuery("size", "20")
	pageSize, err = strconv.Atoi(pageSizeStr)
	if err != nil || pageSize < 1 || pageSize > 100 {
		pageSize = 20
	}

	return page, pageSize
}

func (h *LibraryHandler) CreateLibraryPage(c *gin.Context) {
	data := library.CreateLibraryData{
		Error:  "",
		Fields: make(map[string]string),
	}

	if err := h.RenderPage(c, "Create Library",
		library.CreateLibraryPage(data),
		library.CreateLibraryContent(data)); err != nil {
		h.handleRenderError(c, err, "CreateLibraryPage")
	}
}

func (h *LibraryHandler) CreateLibrary(c *gin.Context) {
	req, err := h.parseLibraryForm(c)
	if err != nil {
		h.renderLibraryForm(c, h.formHandler.ProcessError(err, "CreateLibrary"))
		return
	}

	appCtx, err := middleware.GetAppContext(c)
	if err != nil {
		h.renderLibraryForm(c, FormData{
			Error: "Invalid application state",
		})
		return
	}

	_, err = h.services.Media.CreateLibrary(c.Request.Context(), appCtx.User.ID, req)
	if err != nil {
		h.logger.ErrorContext(c.Request.Context(), "Failed to create library",
			"user_id", appCtx.User.ID,
			"error", err)
		h.renderLibraryForm(c, h.formHandler.ProcessError(err, "CreateLibrary"))
		return
	}

	h.HTMXRedirect(c, "/library")
}

// Helper methods

func (h *LibraryHandler) parseLibraryForm(c *gin.Context) (models.CreateLibraryRequest, error) {
	if err := c.Request.ParseForm(); err != nil {
		return models.CreateLibraryRequest{}, appErr.NewHandlerError(appErr.ErrBadInput, "failed to parse form data", err).
			WithOperation("parseLibraryForm")
	}

	req := models.CreateLibraryRequest{
		Name:    strings.TrimSpace(c.PostForm("libraryName")),
		Folders: make([]models.CreateFolderRequest, 0),
	}

	// Validate library name
	if req.Name == "" {
		return req, appErr.NewHandlerError(appErr.ErrValidation, "library name is required", nil).
			WithOperation("parseLibraryForm").
			WithField("libraryName")
	}

	// Parse folders
	formValues := c.Request.PostForm
	for i := 0; ; i++ {
		nameKey := fmt.Sprintf("folders.%d.name", i)
		pathKey := fmt.Sprintf("folders.%d.path", i)

		name := strings.TrimSpace(formValues.Get(nameKey))
		path := strings.TrimSpace(formValues.Get(pathKey))

		if name == "" && path == "" {
			break
		}

		if name == "" || path == "" {
			return req, appErr.NewHandlerError(appErr.ErrValidation, "both folder name and path are required", nil).
				WithOperation("parseLibraryForm").
				WithField(fmt.Sprintf("folders.%d", i))
		}

		req.Folders = append(req.Folders, models.CreateFolderRequest{
			Name: name,
			Path: path,
		})
	}

	if len(req.Folders) == 0 {
		return req, appErr.NewHandlerError(appErr.ErrValidation, "at least one folder is required", nil).
			WithOperation("parseLibraryForm").
			WithField("folders")
	}

	return req, nil
}

func (h *LibraryHandler) renderLibraryForm(c *gin.Context, formData FormData) {
	h.RenderComponent(c, library.CreateLibraryContent(library.CreateLibraryData{
		Error:  formData.Error,
		Fields: formData.Fields,
	}))
}

func (h *LibraryHandler) handleRenderError(c *gin.Context, err error, operation string) {
	h.logger.ErrorContext(c.Request.Context(), "Render error",
		"error", err,
		"operation", operation)

	h.RenderError(c, appErr.HTTPStatusCode(err), "Failed to render page")
}
