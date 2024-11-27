package error

import (
	"errors"
	"fmt"
	"net/http"
)

// ErrorCode represents a unique identifier for each type of error
type ErrorCode string

// Domain represents the layer where the error originated
type Domain string

const (
	// Domains
	DomainRepository Domain = "repository"
	DomainService    Domain = "service"
	DomainHandler    Domain = "handler"

	// Error codes for different scenarios
	ErrValidation   ErrorCode = "validation_error"
	ErrNotFound     ErrorCode = "not_found"
	ErrConflict     ErrorCode = "conflict"
	ErrInternal     ErrorCode = "internal_error"
	ErrUnauthorized ErrorCode = "unauthorized"
	ErrDatabase     ErrorCode = "database_error"
	ErrBadInput     ErrorCode = "bad_input"
)

// StatusCodeMap maps error codes to HTTP status codes
var StatusCodeMap = map[ErrorCode]int{
	ErrValidation:   http.StatusBadRequest,
	ErrNotFound:     http.StatusNotFound,
	ErrConflict:     http.StatusConflict,
	ErrInternal:     http.StatusInternalServerError,
	ErrUnauthorized: http.StatusUnauthorized,
	ErrDatabase:     http.StatusInternalServerError,
	ErrBadInput:     http.StatusBadRequest,
}

// AppError represents an application error with rich context
type AppError struct {
	// Core error information
	Code      ErrorCode `json:"-"`          // Internal error code
	Domain    Domain    `json:"-"`          // Layer where error originated
	Message   string    `json:"message"`    // User-facing message
	Internal  string    `json:"-"`          // Internal details (logging only)
	Err       error     `json:"-"`          // Original error
	RequestID string    `json:"request_id"` // Request tracking

	// Optional contextual information
	Field     string      `json:"field,omitempty"` // Field that caused the error
	Data      interface{} `json:"data,omitempty"`  // Additional error data
	Operation string      `json:"-"`               // Operation being performed
}

// Error implements the error interface
func (e *AppError) Error() string {
	if e.Field != "" {
		return fmt.Sprintf("%s: %s (%s)", e.Domain, e.Message, e.Field)
	}
	return fmt.Sprintf("%s: %s", e.Domain, e.Message)
}

// Unwrap implements the errors.Unwrap interface
func (e *AppError) Unwrap() error {
	return e.Err
}

// WithField adds field information to the error
func (e *AppError) WithField(field string) *AppError {
	e.Field = field
	return e
}

// WithData adds additional context to the error
func (e *AppError) WithData(data interface{}) *AppError {
	e.Data = data
	return e
}

// WithOperation adds operation information
func (e *AppError) WithOperation(op string) *AppError {
	e.Operation = op
	return e
}

// IsTemporary indicates if the error is temporary and the operation could be retried
func (e *AppError) IsTemporary() bool {
	return e.Code == ErrDatabase
}

// HTTPStatusCode returns the appropriate HTTP status code for the error
func (e *AppError) HTTPStatusCode() int {
	if code, exists := StatusCodeMap[e.Code]; exists {
		return code
	}
	return http.StatusInternalServerError
}

// Error constructors for different layers
func NewError(domain Domain, code ErrorCode, message string, err error) *AppError {
	return &AppError{
		Code:     code,
		Domain:   domain,
		Message:  message,
		Internal: err.Error(),
		Err:      err,
	}
}

// Repository-specific error constructors
func NewRepositoryError(code ErrorCode, message string, err error) *AppError {
	return NewError(DomainRepository, code, message, err)
}

// Service-specific error constructors
func NewServiceError(code ErrorCode, message string, err error) *AppError {
	return NewError(DomainService, code, message, err)
}

// Handler-specific error constructors
func NewHandlerError(code ErrorCode, message string, err error) *AppError {
	return NewError(DomainHandler, code, message, err)
}

func IsNotFound(err error) bool {
	var appErr *AppError
	if errors.As(err, &appErr) {
		return appErr.Code == ErrNotFound
	}
	return false
}

func IsValidation(err error) bool {
	var appErr *AppError
	if errors.As(err, &appErr) {
		return appErr.Code == ErrValidation
	}
	return false
}

func IsConflict(err error) bool {
	var appErr *AppError
	if errors.As(err, &appErr) {
		return appErr.Code == ErrConflict
	}
	return false
}

func IsUnauthorized(err error) bool {
	var appErr *AppError
	if errors.As(err, &appErr) {
		return appErr.Code == ErrUnauthorized
	}
	return false
}

func IsDatabase(err error) bool {
	var appErr *AppError
	if errors.As(err, &appErr) {
		return appErr.Code == ErrDatabase
	}
	return false
}

func IsInternal(err error) bool {
	var appErr *AppError
	if errors.As(err, &appErr) {
		return appErr.Code == ErrInternal
	}
	return false
}

// HandleRepositoryError transforms repository errors into service errors while preserving context
func HandleRepositoryError(err error, operation string, data map[string]interface{}) error {
	// If no data map provided, initialize it
	if data == nil {
		data = make(map[string]interface{})
	}

	var appErr *AppError
	if errors.As(err, &appErr) {
		// Only transform if it's a repository error
		if appErr.Domain == DomainRepository {
			// Add source information to the error data
			errorData := map[string]interface{}{
				"source":  "repository",
				"repo_op": appErr.Operation,
			}
			// Merge with provided data
			for k, v := range data {
				errorData[k] = v
			}

			return NewServiceError(appErr.Code, appErr.Message, appErr.Err).
				WithOperation(operation).
				WithData(errorData)
		}
		// If it's not a repository error, return as is
		return appErr
	}

	// Handle unexpected errors
	return NewServiceError(ErrInternal, "unexpected error", err).
		WithOperation(operation).
		WithData(data)
}

// HandleServiceError transforms service errors into handler errors
func HandleServiceError(err error, operation string, data map[string]interface{}) error {
	if data == nil {
		data = make(map[string]interface{})
	}

	var appErr *AppError
	if errors.As(err, &appErr) {
		// Only transform if it's a service error
		if appErr.Domain == DomainService {
			errorData := map[string]interface{}{
				"source":     "service",
				"service_op": appErr.Operation,
			}
			// Merge with provided data
			for k, v := range data {
				errorData[k] = v
			}

			return NewHandlerError(appErr.Code, appErr.Message, appErr.Err).
				WithOperation(operation).
				WithData(errorData)
		}
		// If it's not a service error, return as is
		return appErr
	}

	// Handle unexpected errors
	return NewHandlerError(ErrInternal, "unexpected error", err).
		WithOperation(operation).
		WithData(data)
}

// Helper function to extract status code
func HTTPStatusCode(err error) int {
	var appErr *AppError
	if errors.As(err, &appErr) {
		return appErr.HTTPStatusCode()
	}
	return http.StatusInternalServerError
}

