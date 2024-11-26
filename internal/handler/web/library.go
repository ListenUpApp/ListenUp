package web

import (
	"errors"
	"fmt"
	"strings"

	errorhandling "github.com/ListenUpApp/ListenUp/internal/error_handling"
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
	data := library.CreateLibraryData{
		Error:  "",
		Fields: make(map[string]string),
	}

	err := h.RenderPage(c, "Create Library",
		library.CreateLibraryPage(data),
		library.CreateLibraryContent(data))

	if err != nil {
		// Log the error if needed
		h.logger.ErrorContext(c.Request.Context(), "Failed to render library page", "error", err)
		return
	}
}

func (h *LibraryHandler) CreateLibrary(c *gin.Context) {
	// Parse form manually since we have nested data
	if err := c.Request.ParseForm(); err != nil {
		h.logger.ErrorContext(c.Request.Context(), "Failed to parse form", "error", err)
		h.RenderComponent(c, library.CreateLibraryContent(library.CreateLibraryData{
			Error:  "Failed to process form data",
			Fields: make(map[string]string),
		}))
		return
	}

	// Create request object
	req := models.CreateLibraryRequest{
		Name:    c.PostForm("libraryName"),
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

	// Get app context
	appCtx, _ := middleware.GetAppContext(c)

	// Create library
	_, err := h.services.Library.CreateLibrary(c.Request.Context(), appCtx.User.ID, req)
	if err != nil {
		h.logger.ErrorContext(c.Request.Context(), "Failed to create library",
			"userId", appCtx.User.ID,
			"error", err,
			"requestData", req)

		var appErr *errorhandling.AppError
		if errors.As(err, &appErr) {
			switch appErr.Type {
			case errorhandling.ErrorTypeValidation:
				h.RenderComponent(c, library.CreateLibraryContent(library.CreateLibraryData{
					Error: appErr.Message,
					Fields: map[string]string{
						"libraryName": appErr.Message,
					},
				}))
			case errorhandling.ErrorTypeConflict:
				h.RenderComponent(c, library.CreateLibraryContent(library.CreateLibraryData{
					Error: appErr.Message,
					Fields: map[string]string{
						"libraryName": "This library name is already taken",
					},
				}))
			default:
				h.RenderComponent(c, library.CreateLibraryContent(library.CreateLibraryData{
					Error:  "An unexpected error occurred. Please try again.",
					Fields: make(map[string]string),
				}))
			}
			return
		}

		// If it's not an AppError, return a generic error
		h.RenderComponent(c, library.CreateLibraryContent(library.CreateLibraryData{
			Error:  "Failed to create library. Please try again",
			Fields: make(map[string]string),
		}))
		return
	}

	h.HTMXRedirect(c, "/library")
}
