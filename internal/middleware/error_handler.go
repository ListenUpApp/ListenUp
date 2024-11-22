package middleware

import (
	"context"
	"errors"
	ErrorHandling "github.com/ListenUpApp/ListenUp/internal/error_handling"
	"github.com/gin-gonic/gin"
	"log/slog"
	"net/http"
)

func ErrorHandler(logger *slog.Logger) gin.HandlerFunc {
	return func(c *gin.Context) {
		c.Next()

		if len(c.Errors) > 0 {
			err := c.Errors.Last().Err
			var appErr *ErrorHandling.AppError

			if errors.As(err, &appErr) {
				// Create context with request ID
				ctx := context.WithValue(c.Request.Context(), "request_id", c.GetString("RequestID"))

				// Log based on error type
				switch appErr.Type {
				case ErrorHandling.ErrorTypeInternal:
					logger.ErrorContext(ctx, "Internal error",
						"error", appErr.Err,
						"detail", appErr.Detail,
						"path", c.Request.URL.Path,
						"method", c.Request.Method)
				default:
					logger.InfoContext(ctx, "Application error",
						"type", string(appErr.Type),
						"message", appErr.Message,
						"path", c.Request.URL.Path,
						"method", c.Request.Method)
				}

				appErr.RequestID = c.GetString("RequestID")
				c.JSON(appErr.Code, gin.H{
					"success": false,
					"error": gin.H{
						"message":    appErr.Message,
						"request_id": appErr.RequestID,
					},
				})
			} else {
				logger.ErrorContext(c.Request.Context(), "Unknown error",
					"error", err,
					"path", c.Request.URL.Path,
					"method", c.Request.Method)

				c.JSON(http.StatusInternalServerError, gin.H{
					"success": false,
					"error": gin.H{
						"message": "An unexpected error occurred",
					},
				})
			}
		}
	}
}
