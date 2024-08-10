package middleware

import (
	"connectrpc.com/connect"
	"context"
	"github.com/ListenUpApp/ListenUp/internal/logger"
	"time"
)

func LoggingInterceptor() connect.UnaryInterceptorFunc {
	return connect.UnaryInterceptorFunc(func(next connect.UnaryFunc) connect.UnaryFunc {
		return connect.UnaryFunc(func(
			ctx context.Context,
			req connect.AnyRequest,
		) (connect.AnyResponse, error) {
			start := time.Now()

			logger.With(
				"method", req.Spec().Procedure,
			).Info("Received request")

			res, err := next(ctx, req)

			duration := time.Since(start)
			logFields := []interface{}{
				"method", req.Spec().Procedure,
				"duration", duration.String(),
			}

			if err != nil {
				connectErr, ok := err.(*connect.Error)
				if ok {
					logFields = append(logFields,
						"status", connectErr.Code().String(),
						"error", connectErr.Message(),
					)
				} else {
					logFields = append(logFields,
						"error", err.Error(),
					)
				}
				logger.With(logFields...).Error("Request failed")
			} else {
				logFields = append(logFields, "status", "OK")
				logger.With(logFields...).Info("Request completed")
			}

			return res, err
		})
	})
}
