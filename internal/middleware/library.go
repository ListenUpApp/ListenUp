package middleware

import (
	"errors"
	"log/slog"

	appcontext "github.com/ListenUpApp/ListenUp/internal/context"
	errorhandling "github.com/ListenUpApp/ListenUp/internal/error_handling"
	"github.com/ListenUpApp/ListenUp/internal/service"
	"github.com/gin-gonic/gin"
)

// WithLibrary enriches the AppContext with library information
func WithLibrary(libraryService *service.LibraryService) gin.HandlerFunc {
	return func(c *gin.Context) {
		appCtx, exists := appcontext.GetAppContextFromGin(c)
		if !exists {
			slog.Error("app context not found in WithLibrary middleware")
			c.Next()
			return
		}

		// Get all libraries to check if any exist
		libraries, err := libraryService.GetUsersLibraries(c, appCtx.User.ID)
		if err != nil {
			slog.Error("error getting libraries in middleware",
				"error", err,
				"userId", appCtx.User.ID)
			c.Next()
			return
		}

		// Get current library if it exists
		activeLibrary, err := libraryService.GetCurrentLibrary(c, appCtx.User.ID)
		if err != nil {
			var appErr *errorhandling.AppError
			if !errors.As(err, &appErr) || appErr.Type != errorhandling.ErrorTypeNotFound {
				slog.Error("error getting active library in middleware",
					"error", err,
					"userId", appCtx.User.ID)
				c.Next()
				return
			}
		}

		// Update context with library information
		appCtx.UpdateWithLibraryInfo(activeLibrary, len(libraries) > 0)

		// Set in both contexts
		appcontext.SetInAll(c, appCtx)

		c.Next()
	}
}
