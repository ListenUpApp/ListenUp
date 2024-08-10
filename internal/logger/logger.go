package logger

import (
	"fmt"
	"go.uber.org/zap"
	"go.uber.org/zap/zapcore"
)

var log *zap.SugaredLogger

const (
	colorReset  = "\033[0m"
	colorRed    = "\033[31m"
	colorGreen  = "\033[32m"
	colorYellow = "\033[33m"
	colorBlue   = "\033[34m"
	colorPurple = "\033[35m"
	colorCyan   = "\033[36m"
	colorWhite  = "\033[37m"
)

func init() {
	config := zap.Config{
		Encoding:         "console",
		Level:            zap.NewAtomicLevelAt(zap.InfoLevel),
		OutputPaths:      []string{"stdout"},
		ErrorOutputPaths: []string{"stderr"},
		EncoderConfig: zapcore.EncoderConfig{
			TimeKey:        "time",
			LevelKey:       "level",
			NameKey:        "logger",
			CallerKey:      "caller",
			FunctionKey:    zapcore.OmitKey,
			MessageKey:     "msg",
			StacktraceKey:  "stacktrace",
			LineEnding:     zapcore.DefaultLineEnding,
			EncodeLevel:    customLevelEncoder,
			EncodeTime:     zapcore.ISO8601TimeEncoder,
			EncodeDuration: zapcore.StringDurationEncoder,
			EncodeCaller:   zapcore.ShortCallerEncoder,
		},
		Sampling: &zap.SamplingConfig{
			Initial:    100,
			Thereafter: 100,
		},
	}

	logger, err := config.Build(zap.AddCaller(), zap.AddStacktrace(zapcore.ErrorLevel))
	if err != nil {
		panic(fmt.Sprintf("Failed to initialize logger: %v", err))
	}

	log = logger.Sugar()
}

func customLevelEncoder(level zapcore.Level, enc zapcore.PrimitiveArrayEncoder) {
	var levelColor string
	switch level {
	case zapcore.DebugLevel:
		levelColor = colorCyan
	case zapcore.InfoLevel:
		levelColor = colorGreen
	case zapcore.WarnLevel:
		levelColor = colorYellow
	case zapcore.ErrorLevel, zapcore.DPanicLevel, zapcore.PanicLevel, zapcore.FatalLevel:
		levelColor = colorRed
	default:
		levelColor = colorReset
	}
	enc.AppendString(fmt.Sprintf("%s%-5s%s", levelColor, level.CapitalString(), colorReset))
}

func Debug(args ...interface{}) {
	log.Debug(args...)
}

func Info(args ...interface{}) {
	log.Info(args...)
}

func Warn(args ...interface{}) {
	log.Warn(args...)
}

func Error(args ...interface{}) {
	log.Error(args...)
}

func Fatal(args ...interface{}) {
	log.Fatal(args...)
}

func With(args ...interface{}) *zap.SugaredLogger {
	return log.With(args...)
}

func WithFields(fields map[string]interface{}) *zap.SugaredLogger {
	return log.With(fields)
}

func Sync() error {
	return log.Sync()
}
