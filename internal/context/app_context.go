package appcontext

import (
	"context"

	"github.com/ListenUpApp/ListenUp/internal/models"
	"github.com/gin-gonic/gin"
)

type contextKey string

const AppContextKey contextKey = "app_context"

type AppContext struct {
	User           models.User
	ActiveLibrary  *models.Library
	LibrariesExist bool
}

func (ctx *AppContext) UpdateWithLibraryInfo(activeLibrary *models.Library, librariesExist bool) {
	ctx.ActiveLibrary = activeLibrary
	ctx.LibrariesExist = librariesExist
}

// GetAppContext retrieves the AppContext from a standard context.Context
func GetAppContext(ctx context.Context) (AppContext, bool) {
	value := ctx.Value(AppContextKey)
	if value == nil {
		return AppContext{}, false
	}
	appCtx, ok := value.(AppContext)
	return appCtx, ok
}

// GetAppContextFromGin retrieves the AppContext from a gin.Context
func GetAppContextFromGin(c *gin.Context) (AppContext, bool) {
	value, exists := c.Get(string(AppContextKey))
	if !exists {
		return AppContext{}, false
	}
	appCtx, ok := value.(AppContext)
	return appCtx, ok
}

// WithAppContext creates a new context.Context with the AppContext
func WithAppContext(ctx context.Context, appCtx AppContext) context.Context {
	return context.WithValue(ctx, AppContextKey, appCtx)
}

// SetInGin sets the AppContext in a gin.Context
func SetInGin(c *gin.Context, appCtx AppContext) {
	c.Set(string(AppContextKey), appCtx)
}

// SetInAll sets the AppContext in both gin.Context and the request's context.Context
func SetInAll(c *gin.Context, appCtx AppContext) {
	SetInGin(c, appCtx)
	c.Request = c.Request.WithContext(WithAppContext(c.Request.Context(), appCtx))
}
