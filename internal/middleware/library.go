package middleware

import (
	"log/slog"

	appcontext "github.com/ListenUpApp/ListenUp/internal/context"
	appErr "github.com/ListenUpApp/ListenUp/internal/error"
	"github.com/ListenUpApp/ListenUp/internal/service"
	"github.com/gin-gonic/gin"
)

// WithLibrary enriches the AppContext with library information
func WithLibrary(libraryService *service.MediaService) gin.HandlerFunc {
	return func(c *gin.Context) {
		// Get app context
		appCtx, err := GetAppContext(c)
		if err != nil {
			slog.ErrorContext(c.Request.Context(), "App context not found in WithLibrary middleware",
				"error", err)
			c.Next()
			return
		}

		// Get all libraries to check if any exist
		libraries, err := libraryService.GetUsersLibraries(c, appCtx.User.ID)
		if err != nil {
			slog.ErrorContext(c.Request.Context(), "Error getting libraries in middleware",
				"error", err,
				"user_id", appCtx.User.ID)
			c.Next()
			return
		}

		// Get current library if it exists
		activeLibrary, err := libraryService.GetCurrentLibrary(c, appCtx.User.ID)
		if err != nil {
			// Only continue if it's a not found error
			if !appErr.IsNotFound(err) {
				slog.ErrorContext(c.Request.Context(), "Error getting active library in middleware",
					"error", err,
					"user_id", appCtx.User.ID)
				c.Next()
				return
			}
			// Not found is okay - user might not have an active library yet
		}

		// Update context with library information
		appCtx.UpdateWithLibraryInfo(activeLibrary, len(libraries) > 0)

		// Set in both contexts
		appcontext.SetInAll(c, appCtx)

		c.Next()
	}
}
