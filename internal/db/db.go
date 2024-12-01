package db

import (
	"context"
	"fmt"
	"github.com/ListenUpApp/ListenUp/internal/ent"
	logging "github.com/ListenUpApp/ListenUp/internal/logger"
	"strings"
	"time"
)

type RetryOptions struct {
	MaxRetries   int
	BaseDelay    time.Duration
	MaxDelay     time.Duration
	EnableJitter bool
	Logger       *logging.AppLogger
}

var DefaultRetryOptions = RetryOptions{
	MaxRetries:   5,
	BaseDelay:    100 * time.Millisecond,
	MaxDelay:     5 * time.Second,
	EnableJitter: true,
}

// WithRetry wraps a database operation with retry logic
func WithRetry[T any](ctx context.Context, opts RetryOptions, operation func(ctx context.Context) (T, error)) (T, error) {
	var lastErr error
	var zero T

	for attempt := 0; attempt < opts.MaxRetries; attempt++ {
		if attempt > 0 {
			delay := calculateDelay(attempt, opts)
			if opts.Logger != nil {
				opts.Logger.InfoContext(ctx, "Retrying database operation",
					"attempt", attempt+1,
					"delay_ms", delay.Milliseconds(),
				)
			}
			time.Sleep(delay)
		}

		result, err := operation(ctx)
		if err == nil {
			return result, nil
		}

		lastErr = err
		if !isLockError(err) {
			return zero, err
		}
	}

	return zero, fmt.Errorf("operation failed after %d retries: %w", opts.MaxRetries, lastErr)
}

// WithRetryTx wraps a transaction with retry logic
func WithRetryTx[T any](ctx context.Context, client *ent.Client, opts RetryOptions, operation func(ctx context.Context, tx *ent.Tx) (T, error)) (T, error) {
	return WithRetry(ctx, opts, func(ctx context.Context) (T, error) {
		var zero T
		tx, err := client.Tx(ctx)
		if err != nil {
			return zero, err
		}
		defer tx.Rollback()

		result, err := operation(ctx, tx)
		if err != nil {
			return zero, err
		}

		if err := tx.Commit(); err != nil {
			return zero, err
		}

		return result, nil
	})
}

func calculateDelay(attempt int, opts RetryOptions) time.Duration {
	delay := opts.BaseDelay * time.Duration(1<<uint(attempt))
	if delay > opts.MaxDelay {
		delay = opts.MaxDelay
	}

	if opts.EnableJitter {
		// Add jitter between 90% and 110% of the delay
		jitter := 0.9 + 0.2*float64(time.Now().Nanosecond())/1e9
		delay = time.Duration(float64(delay) * jitter)
	}

	return delay
}

func isLockError(err error) bool {
	if err == nil {
		return false
	}
	errMsg := err.Error()
	return strings.Contains(errMsg, "database is locked") ||
		strings.Contains(errMsg, "database table is locked") ||
		strings.Contains(errMsg, "SQLITE_BUSY") ||
		strings.Contains(errMsg, "SQLITE_LOCKED")
}
