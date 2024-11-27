package middleware

import (
	"context"
	"errors"
	"net/http"
	"time"

	appErr "github.com/ListenUpApp/ListenUp/internal/error"
	logging "github.com/ListenUpApp/ListenUp/internal/logger"
	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
)

type RequestLogger struct {
	logger *logging.AppLogger
}

func NewRequestLogger(logger *logging.AppLogger) *RequestLogger {
	return &RequestLogger{
		logger: logger,
	}
}

func (rl *RequestLogger) Logger() gin.HandlerFunc {
	return func(c *gin.Context) {
		start := time.Now()
		requestID := uuid.New().String()
		path := c.Request.URL.Path
		query := c.Request.URL.RawQuery

		// Create context with request values
		ctx := context.WithValue(c.Request.Context(), "request_id", requestID)
		ctx = context.WithValue(ctx, "component", "http")
		ctx = context.WithValue(ctx, "operation", path)

		// Add request ID to headers
		c.Header("X-Request-ID", requestID)
		c.Set("RequestID", requestID)

		// Update context
		c.Request = c.Request.WithContext(ctx)

		// Log incoming request
		rl.logger.InfoContext(ctx, "Request started",
			"method", c.Request.Method,
			"path", path,
			"query", query,
			"client_ip", c.ClientIP(),
			"user_agent", c.Request.UserAgent(),
		)

		// Process request
		c.Next()

		// Calculate duration
		duration := time.Since(start)

		// Get route handler name if available
		handlerName := c.HandlerName()

		// Process any errors
		var logErr error
		if len(c.Errors) > 0 {
			logErr = c.Errors.Last().Err
		}

		// Determine status code
		status := c.Writer.Status()

		// Prepare log fields
		fields := []any{
			"status", status,
			"duration", duration,
			"size", c.Writer.Size(),
			"handler", handlerName,
		}

		// Add error information if present
		if logErr != nil {
			var appErr *appErr.AppError
			if errors.As(logErr, &appErr) {
				fields = append(fields,
					"error_code", string(appErr.Code),
					"error_domain", string(appErr.Domain),
					"error_message", appErr.Message,
				)

				if appErr.Operation != "" {
					fields = append(fields, "operation", appErr.Operation)
				}

				if appErr.Internal != "" {
					fields = append(fields,
						"error_internal", appErr.Internal,
						"error_original", appErr.Err.Error(),
					)
				}

				if appErr.Field != "" {
					fields = append(fields, "error_field", appErr.Field)
				}

				// Set error response
				appErr.RequestID = requestID
				c.JSON(appErr.HTTPStatusCode(), gin.H{
					"success": false,
					"error": gin.H{
						"message":    appErr.Message,
						"request_id": appErr.RequestID,
						"field":      appErr.Field,
						"data":       appErr.Data,
					},
				})
			} else {
				fields = append(fields,
					"error", logErr.Error(),
				)

				// Set generic error response
				c.JSON(http.StatusInternalServerError, gin.H{
					"success": false,
					"error": gin.H{
						"message":    "An unexpected error occurred",
						"request_id": requestID,
					},
				})
			}
		}

		// Log based on status code
		switch {
		case status >= 500:
			rl.logger.ErrorContext(ctx, "Request failed", fields...)
		case status >= 400:
			rl.logger.WarnContext(ctx, "Request completed with client error", fields...)
		case status >= 300:
			rl.logger.InfoContext(ctx, "Request redirected", fields...)
		default:
			rl.logger.InfoContext(ctx, "Request completed successfully", fields...)
		}
	}
}