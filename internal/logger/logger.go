package logging

import (
	"context"
	"fmt"
	"log/slog"
	"os"
	"runtime"
	"strings"
	"time"
)

// LogLevel represents logging levels
type LogLevel string

const (
	LevelDebug LogLevel = "DEBUG"
	LevelInfo  LogLevel = "INFO"
	LevelWarn  LogLevel = "WARN"
	LevelError LogLevel = "ERROR"
)

// Config holds logger configuration
type Config struct {
	Environment string
	LogLevel    LogLevel
	Pretty      bool
	OutputPath  string
	AddSource   bool
	TimeFormat  string
}

// Colors for pretty printing
const (
	colorReset  = "\033[0m"
	colorRed    = "\033[31m"
	colorGreen  = "\033[32m"
	colorYellow = "\033[33m"
	colorBlue   = "\033[34m"
	colorPurple = "\033[35m"
	colorCyan   = "\033[36m"
	colorGray   = "\033[37m"
)

// contextKey type for context values
type contextKey string

const (
	requestIDKey contextKey = "request_id"
	userIDKey    contextKey = "user_id"
	traceIDKey   contextKey = "trace_id"
	componentKey contextKey = "component"
	operationKey contextKey = "operation"
)

// AppLogger wraps slog.Logger with additional functionality
type AppLogger struct {
	*slog.Logger
	cfg Config
}

// CustomHandler extends slog.Handler with additional functionality
type CustomHandler struct {
	slog.Handler
	cfg Config
}

// Handle implements custom log handling
func (h *CustomHandler) Handle(ctx context.Context, r slog.Record) error {
	// Enhance the record with additional context
	attrs := make([]slog.Attr, 0, r.NumAttrs()+5)

	// Add basic context
	attrs = append(attrs,
		slog.String("environment", h.cfg.Environment),
		slog.Time("timestamp", time.Now().UTC()),
	)

	// Add source information if enabled
	if h.cfg.AddSource {
		if file, line, fn := getRuntimeContext(4); file != "" {
			attrs = append(attrs,
				slog.String("file", file),
				slog.Int("line", line),
				slog.String("func", fn),
			)
		}
	}

	// Add context values if present
	contextKeys := []contextKey{requestIDKey, userIDKey, traceIDKey, componentKey, operationKey}
	for _, key := range contextKeys {
		if val := ctx.Value(key); val != nil {
			attrs = append(attrs, slog.String(string(key), fmt.Sprint(val)))
		}
	}

	// Add original attributes
	r.Attrs(func(a slog.Attr) bool {
		attrs = append(attrs, a)
		return true
	})

	// Create new record with all attributes
	newRecord := slog.NewRecord(r.Time, r.Level, r.Message, r.PC)
	for _, attr := range attrs {
		newRecord.AddAttrs(attr)
	}

	return h.Handler.Handle(ctx, newRecord)
}

// PrettyHandler formats logs for human readability
type PrettyHandler struct {
	CustomHandler
}

func (h *PrettyHandler) Handle(ctx context.Context, r slog.Record) error {
	var attrs []slog.Attr
	r.Attrs(func(a slog.Attr) bool {
		attrs = append(attrs, a)
		return true
	})

	// Get level color
	levelColor := getLevelColor(r.Level)

	// Format timestamp
	timestamp := time.Now().Format(h.cfg.TimeFormat)

	// Format source info if enabled
	sourceInfo := ""
	if h.cfg.AddSource {
		if file, line, fn := getRuntimeContext(4); file != "" {
			sourceInfo = fmt.Sprintf(" %s%s:%d%s %s%s%s",
				colorGray, file, line, colorReset,
				colorBlue, fn, colorReset)
		}
	}

	// Format attributes
	attrStr := formatAttributes(attrs)

	// Print formatted log
	fmt.Printf("%s%s%s [%s%-5s%s]%s %s%s%s\n",
		colorGray, timestamp, colorReset,
		levelColor, r.Level.String(), colorReset,
		sourceInfo,
		r.Message,
		attrStr,
		colorReset)

	return nil
}

// New creates a new configured logger
func New(cfg Config) *AppLogger {
	// Set defaults
	if cfg.TimeFormat == "" {
		cfg.TimeFormat = time.RFC3339
	}

	var handler slog.Handler
	opts := &slog.HandlerOptions{
		Level:     getLogLevel(cfg.LogLevel),
		AddSource: cfg.AddSource,
	}

	if cfg.Pretty {
		handler = &PrettyHandler{
			CustomHandler: CustomHandler{
				Handler: slog.NewTextHandler(os.Stdout, opts),
				cfg:     cfg,
			},
		}
	} else {
		handler = &CustomHandler{
			Handler: slog.NewJSONHandler(os.Stdout, opts),
			cfg:     cfg,
		}
	}

	return &AppLogger{
		Logger: slog.New(handler),
		cfg:    cfg,
	}
}

// Helper methods

func getLogLevel(level LogLevel) slog.Level {
	switch level {
	case LevelDebug:
		return slog.LevelDebug
	case LevelInfo:
		return slog.LevelInfo
	case LevelWarn:
		return slog.LevelWarn
	case LevelError:
		return slog.LevelError
	default:
		return slog.LevelInfo
	}
}

func getLevelColor(level slog.Level) string {
	switch level {
	case slog.LevelDebug:
		return colorCyan
	case slog.LevelInfo:
		return colorGreen
	case slog.LevelWarn:
		return colorYellow
	case slog.LevelError:
		return colorRed
	default:
		return colorReset
	}
}

func getRuntimeContext(skip int) (string, int, string) {
	pc, file, line, ok := runtime.Caller(skip)
	if !ok {
		return "", 0, ""
	}
	fn := runtime.FuncForPC(pc).Name()
	return shortenPath(file), line, shortenFunc(fn)
}

func shortenPath(file string) string {
	split := strings.Split(file, "/")
	if len(split) > 2 {
		return strings.Join(split[len(split)-2:], "/")
	}
	return file
}

func shortenFunc(fn string) string {
	split := strings.Split(fn, ".")
	return split[len(split)-1]
}

func formatAttributes(attrs []slog.Attr) string {
	var sb strings.Builder
	for _, attr := range attrs {
		// Skip certain attributes that are handled separately
		if attr.Key == "timestamp" || attr.Key == "level" || attr.Key == "msg" {
			continue
		}

		// Format the value
		value := ""
		switch v := attr.Value.Any().(type) {
		case error:
			value = v.Error()
		case fmt.Stringer:
			value = v.String()
		default:
			value = fmt.Sprint(v)
		}

		sb.WriteString(fmt.Sprintf(" %s%s=%s%s",
			colorPurple,
			attr.Key,
			value,
			colorReset))
	}
	return sb.String()
}

// WithContext adds context values to the logger
func (l *AppLogger) WithContext(ctx context.Context) *AppLogger {
	return &AppLogger{
		Logger: l.Logger.With(
			"request_id", ctx.Value(requestIDKey),
			"user_id", ctx.Value(userIDKey),
			"trace_id", ctx.Value(traceIDKey),
			"component", ctx.Value(componentKey),
			"operation", ctx.Value(operationKey),
		),
		cfg: l.cfg,
	}
}
