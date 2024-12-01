package db

import (
	"context"
	"fmt"
	"github.com/ListenUpApp/ListenUp/internal/ent"
	logging "github.com/ListenUpApp/ListenUp/internal/logger"
	"time"
)

type BaseRepository struct {
	client *ent.Client
	logger *logging.AppLogger
	opts   RetryOptions
}

func NewBaseRepository(client *ent.Client, logger *logging.AppLogger) *BaseRepository {
	if client == nil {
		panic("nil client provided to NewBaseRepository")
	}
	return &BaseRepository{
		client: client,
		logger: logger,
		opts:   DefaultRetryOptions,
	}
}

// Instead of having generic methods on the struct, let's create wrapper functions
func DoWithRetry[T any](r *BaseRepository, ctx context.Context, operation func(ctx context.Context) (T, error)) (T, error) {
	r.opts.Logger = r.logger
	return WithRetry(ctx, r.opts, operation)
}

func DoWithRetryTx[T any](base *BaseRepository, ctx context.Context, operation func(ctx context.Context, tx *ent.Tx) (T, error)) (T, error) {
	var lastErr error
	var zero T

	for attempt := 0; attempt < base.opts.MaxRetries; attempt++ {
		if attempt > 0 {
			backoffMs := int64(base.opts.BaseDelay.Milliseconds()) * (1 << attempt)
			jitter := 0.9 + 0.2*float64(time.Now().Nanosecond())/1e9
			backoff := time.Duration(float64(backoffMs)*jitter) * time.Millisecond

			if base.logger != nil {
				base.logger.InfoContext(ctx, "Retrying database operation",
					"attempt", attempt+1,
					"delay_ms", backoff.Milliseconds(),
				)
			}

			time.Sleep(backoff)
		}

		if base.client == nil {
			return zero, fmt.Errorf("nil database client")
		}

		tx, err := base.client.Tx(ctx)
		if err != nil {
			lastErr = err
			if !isLockError(err) {
				return zero, err
			}
			continue
		}

		result, err := operation(ctx, tx)
		if err != nil {
			tx.Rollback()
			lastErr = err
			if !isLockError(err) {
				return zero, err
			}
			continue
		}

		if err := tx.Commit(); err != nil {
			lastErr = err
			if !isLockError(err) {
				return zero, err
			}
			continue
		}

		return result, nil
	}

	return zero, fmt.Errorf("operation failed after %d retries: %w", base.opts.MaxRetries, lastErr)
}
