package web

import (
	"errors"
	"net/http"

	appErr "github.com/ListenUpApp/ListenUp/internal/error"
	"github.com/gin-gonic/gin"
)

// FormHandler provides common functionality for handling forms and form errors
type FormHandler struct {
	errorMessages map[appErr.ErrorCode]string
}

// NewFormHandler creates a new form handler with default error messages
func NewFormHandler() *FormHandler {
	return &FormHandler{
		errorMessages: map[appErr.ErrorCode]string{
			appErr.ErrValidation:   "Please check the form for errors",
			appErr.ErrConflict:     "A record with this value already exists",
			appErr.ErrUnauthorized: "Invalid credentials",
			appErr.ErrInternal:     "An unexpected error occurred. Please try again",
			appErr.ErrNotFound:     "Resource not found",
		},
	}
}

// FormData represents common form data structure
type FormData struct {
	Error  string
	Fields map[string]string
}

// ProcessError handles service errors and returns appropriate form data
func (f *FormHandler) ProcessError(err error, operation string) FormData {
	data := FormData{
		Error:  f.getErrorMessage(err),
		Fields: make(map[string]string),
	}

	// Transform service error to handler error
	handlerErr := appErr.HandleServiceError(err, operation, nil)

	var appError *appErr.AppError
	if errors.As(handlerErr, &appError) {
		if appError.Data != nil {
			if fields, ok := appError.Data.(map[string]string); ok {
				data.Fields = fields
			}
		}
		// Handle field-specific errors
		if appError.Field != "" {
			data.Fields[appError.Field] = appError.Message
		}
	}

	return data
}

// getErrorMessage returns an appropriate user-facing error message
func (f *FormHandler) getErrorMessage(err error) string {
	var appError *appErr.AppError
	if errors.As(err, &appError) {
		if msg, exists := f.errorMessages[appError.Code]; exists {
			return msg
		}
	}
	return "An unexpected error occurred. Please try again"
}

// AddErrorMessage allows adding custom error messages for specific error codes
func (f *FormHandler) AddErrorMessage(code appErr.ErrorCode, message string) {
	f.errorMessages[code] = message
}

func (h *FolderHandler) handleValidationError(c *gin.Context, field, message string) {
	err := appErr.NewHandlerError(appErr.ErrValidation, message, nil).
		WithOperation("FolderHandler").
		WithField(field)

	formData := h.formHandler.ProcessError(err, "validation")
	h.RenderError(c, http.StatusBadRequest, formData.Error)
}
