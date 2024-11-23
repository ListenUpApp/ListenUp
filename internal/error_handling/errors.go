package errorhandling

import (
	"net/http"
)

type ErrorType string

const (
	ErrorTypeValidation   ErrorType = "VALIDATION_ERROR"
	ErrorTypeNotFound     ErrorType = "NOT_FOUND"
	ErrorTypeConflict     ErrorType = "CONFLICT"
	ErrorTypeInternal     ErrorType = "INTERNAL_ERROR"
	ErrorTypeUnauthorized ErrorType = "UNAUTHORIZED"
)

// AppError represents a custom error structure
type AppError struct {
	Type      ErrorType   `json:"-"`              // Internal type (not exposed)
	Message   string      `json:"message"`        // User-facing message
	Detail    string      `json:"-"`              // Internal details
	Code      int         `json:"-"`              // HTTP status code
	Err       error       `json:"-"`              // Original error
	RequestID string      `json:"request_id"`     // Request tracking
	Data      interface{} `json:"data,omitempty"` // Optional data
}

// Error implements the error interface
func (e *AppError) Error() string {
	return e.Message
}

// WithData adds data to an AppError and returns it
func (e *AppError) WithData(data interface{}) *AppError {
	e.Data = data
	return e
}

// Error constructors for different layers
func NewValidationError(message string) *AppError {
	return &AppError{
		Type:    ErrorTypeValidation,
		Message: message,
		Code:    http.StatusBadRequest,
	}
}

func NewConflictError(message string) *AppError {
	return &AppError{
		Type:    ErrorTypeConflict,
		Message: message,
		Code:    http.StatusConflict,
	}
}

func NewNotFoundError(message string) *AppError {
	return &AppError{
		Type:    ErrorTypeNotFound,
		Message: message,
		Code:    http.StatusNotFound,
	}
}

func NewInternalError(err error, message string) *AppError {
	return &AppError{
		Type:    ErrorTypeInternal,
		Message: message,
		Detail:  err.Error(),
		Code:    http.StatusInternalServerError,
		Err:     err,
	}
}

func NewUnauthorizedError(message string) *AppError {
	return &AppError{
		Type:    ErrorTypeUnauthorized,
		Message: message,
		Code:    http.StatusUnauthorized,
	}
}
