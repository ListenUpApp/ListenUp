package middleware

import (
	"context"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
	"time"
)

func LoggingInterceptor(logger Logger) grpc.UnaryServerInterceptor {
	return func(ctx context.Context, req interface{}, info *grpc.UnaryServerInfo, handler grpc.UnaryHandler) (interface{}, error) {
		start := time.Now()

		logger.With(
			"method", info.FullMethod,
		).Info("Received request")

		res, err := handler(ctx, req)

		duration := time.Since(start)
		logFields := []interface{}{
			"method", info.FullMethod,
			"duration", duration.String(),
		}

		if err != nil {
			st, ok := status.FromError(err)
			if ok {
				logFields = append(logFields,
					"status", st.Code().String(),
					"error", st.Message(),
				)
			} else {
				logFields = append(logFields,
					"error", err.Error(),
				)
			}
			logger.With(logFields...).Error("Request failed")
		} else {
			logFields = append(logFields, "status", codes.OK.String())
			logger.With(logFields...).Info("Request completed")
		}

		return res, err
	}
}

type Logger interface {
	With(keyvals ...interface{}) Logger
	Info(msg string)
	Error(msg string)
}
