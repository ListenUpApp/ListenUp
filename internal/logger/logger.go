package logger

import (
	"context"
	"fmt"
	"github.com/ListenUpApp/ListenUp/internal/config"
	"log/slog"
	"os"
	"time"
)

// Level represents logging levels
type Level string

const (
	LevelDebug Level = "DEBUG"
	LevelInfo  Level = "INFO"
	LevelWarn  Level = "WARN"
	LevelError Level = "ERROR"
)

// Config holds logger configuration
type Config struct {
	Environment string // e.g., development, staging, production
	LogLevel    Level
	Prettier    bool // Use pretty printing in development
}

// CustomHandler extends slog.Handler with additional functionality
type CustomHandler struct {
	slog.Handler
	environment string
}

// enhanceAttributes adds common fields to all log entries
func (h *CustomHandler) enhanceAttributes(ctx context.Context, attrs []slog.Attr) []slog.Attr {
	// Add timestamp in RFC3339Nano format
	attrs = append(attrs, slog.Time("timestamp", time.Now().UTC()))

	// Add environment
	attrs = append(attrs, slog.String("environment", h.environment))

	// Add request ID from context if available
	if requestID := ctx.Value("request_id"); requestID != nil {
		attrs = append(attrs, slog.String("request_id", requestID.(string)))
	}

	return attrs
}

func (h *CustomHandler) Handle(ctx context.Context, r slog.Record) error {
	attrs := make([]slog.Attr, 0, r.NumAttrs()+3)
	r.Attrs(func(a slog.Attr) bool {
		attrs = append(attrs, a)
		return true
	})

	attrs = h.enhanceAttributes(ctx, attrs)

	// Create new record with enhanced attributes
	newRecord := slog.NewRecord(r.Time, r.Level, r.Message, r.PC)
	for _, attr := range attrs {
		newRecord.AddAttrs(attr)
	}

	return h.Handler.Handle(ctx, newRecord)
}

// PrettyHandler formats logs in a human-readable format for development
type PrettyHandler struct {
	CustomHandler
}

func (h *PrettyHandler) Handle(ctx context.Context, r slog.Record) error {
	var attrs []slog.Attr
	r.Attrs(func(a slog.Attr) bool {
		attrs = append(attrs, a)
		return true
	})

	attrs = h.enhanceAttributes(ctx, attrs)

	// Format time as RFC3339
	timeStr := r.Time.Format(time.RFC3339)

	// Build pretty output
	level := r.Level.String()
	message := r.Message

	// Format attributes
	attrStr := ""
	for _, attr := range attrs {
		if attr.Key != "timestamp" { // Skip timestamp as we already formatted it
			attrStr += fmt.Sprintf(" %s=%v", attr.Key, attr.Value)
		}
	}

	// Print in color based on level
	var colorCode string
	switch r.Level {
	case slog.LevelDebug:
		colorCode = "\033[36m" // Cyan
	case slog.LevelInfo:
		colorCode = "\033[32m" // Green
	case slog.LevelWarn:
		colorCode = "\033[33m" // Yellow
	case slog.LevelError:
		colorCode = "\033[31m" // Red
	}

	fmt.Printf("%s%s [%s] %-44s%s%s\033[0m\n",
		colorCode, timeStr, level, message, attrStr, colorCode)

	return nil
}

// New creates a new configured logger
func New(cfg config.LoggerConfig) *slog.Logger {
	var level slog.Level
	switch cfg.Level {
	case "debug":
		level = slog.LevelDebug
	case "info":
		level = slog.LevelInfo
	case "warn":
		level = slog.LevelWarn
	case "error":
		level = slog.LevelError
	default:
		level = slog.LevelInfo
	}

	opts := &slog.HandlerOptions{
		Level:     level,
		AddSource: cfg.Level == "debug",
	}

	return slog.New(slog.NewJSONHandler(os.Stdout, opts))
}
