package repository

import (
	"context"
	logging "github.com/ListenUpApp/ListenUp/internal/logger"
	"strings"
	"time"
)

// RetryOptions configures the retry behavior
type RetryOptions struct {
	MaxAttempts     int
	InitialDelay    time.Duration
	MaxDelay        time.Duration
	BackoffFactor   float64
	RetryableErrors []string
}

// DefaultRetryOptions provides sensible defaults for SQLite operations
func DefaultRetryOptions() RetryOptions {
	return RetryOptions{
		MaxAttempts:   5,
		InitialDelay:  100 * time.Millisecond,
		MaxDelay:      5 * time.Second,
		BackoffFactor: 2.0,
		RetryableErrors: []string{
			"database is locked",
			"SQLITE_BUSY",
		},
	}
}

// WithRetry wraps a repository operation with retry logic
func WithRetry[T any](
	ctx context.Context,
	operation func(context.Context) (T, error),
	opts RetryOptions,
	logger *logging.AppLogger,
) (T, error) {
	var result T
	var err error
	delay := opts.InitialDelay

	for attempt := 1; attempt <= opts.MaxAttempts; attempt++ {
		result, err = operation(ctx)
		if err == nil {
			return result, nil
		}

		// Check if error is retryable
		shouldRetry := false
		for _, retryableErr := range opts.RetryableErrors {
			if strings.Contains(err.Error(), retryableErr) {
				shouldRetry = true
				break
			}
		}

		if !shouldRetry || attempt == opts.MaxAttempts {
			return result, err
		}

		// Log retry attempt
		if logger != nil {
			logger.Warn("retrying operation due to database lock",
				"attempt", attempt,
				"delay", delay.String(),
				"error", err)
		}

		// Wait before retrying
		select {
		case <-ctx.Done():
			return result, ctx.Err()
		case <-time.After(delay):
		}

		// Calculate next delay with exponential backoff
		delay = time.Duration(float64(delay) * opts.BackoffFactor)
		if delay > opts.MaxDelay {
			delay = opts.MaxDelay
		}
	}

	return result, err
}
