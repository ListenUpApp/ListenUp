package middleware

import (
	"errors"
	"log/slog"
	"net/http"
	"strings"

	appcontext "github.com/ListenUpApp/ListenUp/internal/context"
	"github.com/ListenUpApp/ListenUp/internal/service"
	"github.com/ListenUpApp/ListenUp/internal/util"
	"github.com/gin-gonic/gin"
)

// WebAuth handles web-based authentication via cookies
func WebAuth() gin.HandlerFunc {
	return func(c *gin.Context) {
		tokenString, err := util.GetAuthCookie(c)
		if err != nil {
			slog.Info("no auth cookie found, redirecting to login",
				"path", c.Request.URL.Path)
			c.Header("HX-Redirect", "/auth/login")
			c.Header("Location", "/auth/login")
			c.Status(http.StatusTemporaryRedirect)
			c.Abort()
			return
		}

		claims, err := util.ParseToken(tokenString)
		if err != nil {
			slog.Info("invalid token, redirecting to login",
				"path", c.Request.URL.Path,
				"error", err)
			util.ClearAuthCookie(c)
			c.Header("HX-Redirect", "/auth/login")
			c.Header("Location", "/auth/login")
			c.Status(http.StatusTemporaryRedirect)
			c.Abort()
			return
		}

		c.Set("claims", claims)
		c.Next()
	}
}

// APIAuth handles API authentication via Bearer tokens
func APIAuth() gin.HandlerFunc {
	return func(c *gin.Context) {
		authHeader := c.GetHeader("Authorization")
		if !strings.HasPrefix(authHeader, "Bearer ") {
			c.JSON(http.StatusUnauthorized, gin.H{
				"error":   "unauthorized",
				"message": "Valid authentication token required",
			})
			c.Abort()
			return
		}

		token := strings.TrimPrefix(authHeader, "Bearer ")
		claims, err := util.ParseToken(token)
		if err != nil {
			var response gin.H
			if errors.Is(err, util.ErrExpiredToken) {
				response = gin.H{
					"error":   "token_expired",
					"message": "Token has expired",
				}
			} else {
				response = gin.H{
					"error":   "invalid_token",
					"message": "Invalid authentication token",
				}
			}
			c.JSON(http.StatusUnauthorized, response)
			c.Abort()
			return
		}

		c.Set("claims", claims)
		c.Next()
	}
}

// WithUser injects the user into the request context
func WithUser(userService *service.UserService) gin.HandlerFunc {
	return func(c *gin.Context) {
		claims, err := util.GetCustomClaims(c)
		if err != nil {
			slog.Error("failed to get claims from context",
				"error", err,
				"path", c.Request.URL.Path)
			c.Next()
			return
		}

		user, err := userService.GetUserByID(c, claims.UserID)
		if err != nil {
			slog.Error("failed to get user from claims",
				"error", err,
				"user_id", claims.UserID,
				"path", c.Request.URL.Path)
			c.Next()
			return
		}

		ctx := appcontext.AppContext{
			User: *user,
		}

		// Set in both contexts
		appcontext.SetInAll(c, ctx)

		slog.Info("user context set successfully",
			"user_id", user.ID,
			"path", c.Request.URL.Path)
		c.Next()
	}
}

// GetAppContext retrieves the AppContext from the gin context
func GetAppContext(c *gin.Context) (appcontext.AppContext, bool) {
	ctx, exists := c.Get("app_context")
	if !exists {
		return appcontext.AppContext{}, false
	}
	appCtx, ok := ctx.(appcontext.AppContext)
	return appCtx, ok
}
