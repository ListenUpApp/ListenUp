package config

import (
	"fmt"
	"github.com/spf13/viper"
	"os"
	"path/filepath"
	"strings"
)

type Config struct {
	App      AppConfig
	Server   ServerConfig
	Database DatabaseConfig
	Logger   LoggerConfig
	Cookie   CookieConfig
	Metadata MetadataConfig
}

type AppConfig struct {
	Environment string `mapstructure:"ENV"`
	Debug       bool   `mapstructure:"DEBUG"`
}

type ServerConfig struct {
	Port int `mapstructure:"PORT"`
}

type DatabaseConfig struct {
	Name string `mapstructure:"DB_NAME"`
}

type LoggerConfig struct {
	Level string `mapstructure:"LOG_LEVEL"`
}

type CookieConfig struct {
	Domain     string
	CookiePath string
}

type MetadataConfig struct {
	BasePath       string  `mapstructure:"BasePath"`
	MaxFileSize    int64   `mapstructure:"MaxFileSize"`
	WebPQuality    float32 `mapstructure:"WebPQuality"`
	Sharpening     float64 `mapstructure:"Sharpening"`
	NoiseReduction float64 `mapstructure:"NoiseReduction"`
}

func LoadConfig(path string) (*Config, error) {
	viper.AddConfigPath(path)
	viper.SetConfigName(".env")
	viper.SetConfigType("env")

	// Set defaults for nested config
	viper.SetDefault("App.Environment", "development")
	viper.SetDefault("App.Debug", true)
	viper.SetDefault("Server.Port", 8080)
	viper.SetDefault("Database.Name", "listenup")
	viper.SetDefault("Logger.Level", "info")
	viper.SetDefault("Cookie.Domain", "")
	viper.SetDefault("Cookie.CookiePath", "/")
	viper.SetDefault("Metadata.BasePath", "~/ListenUp/metadata")
	viper.SetDefault("Metadata.MaxFileSize", 10*1024*1024) // 10MB
	viper.SetDefault("Metadata.WebPQuality", 85)
	viper.SetDefault("Metadata.Sharpening", 0.3)
	viper.SetDefault("Metadata.NoiseReduction", 0.2)

	// Bind environment variables
	viper.BindEnv("App.Environment", "ENV")
	viper.BindEnv("App.Debug", "DEBUG")
	viper.BindEnv("Server.Port", "PORT")
	viper.BindEnv("Database.Name", "DB_NAME")
	viper.BindEnv("Logger.Level", "LOG_LEVEL")
	viper.BindEnv("Metadata.BasePath", "METADATA_PATH")
	viper.BindEnv("Metadata.MaxFileSize", "IMAGE_MAX_FILE_SIZE")
	viper.BindEnv("Metadata.WebPQuality", "IMAGE_WEBP_QUALITY")
	viper.BindEnv("Metadata.Sharpening", "IMAGE_SHARPENING")
	viper.BindEnv("Metadata.NoiseReduction", "IMAGE_NOISE_REDUCTION")

	if err := viper.ReadInConfig(); err != nil {
		if _, ok := err.(viper.ConfigFileNotFoundError); !ok {
			return nil, fmt.Errorf("error reading config file: %w", err)
		}
	}

	viper.AutomaticEnv()

	var config Config
	if err := viper.Unmarshal(&config); err != nil {
		return nil, fmt.Errorf("error unmarshaling config: %w", err)
	}

	// Expand the metadata base path
	homeDir, err := os.UserHomeDir()
	if err != nil {
		return nil, fmt.Errorf("failed to get home directory: %w", err)
	}

	// Replace ~ with actual home directory if present
	if strings.HasPrefix(config.Metadata.BasePath, "~/") {
		config.Metadata.BasePath = filepath.Join(homeDir, config.Metadata.BasePath[2:])
	} else if config.Metadata.BasePath == "" {
		// If no path specified, use default
		config.Metadata.BasePath = filepath.Join(homeDir, "ListenUp", "metadata")
	}

	// Ensure the path is absolute
	if !filepath.IsAbs(config.Metadata.BasePath) {
		absPath, err := filepath.Abs(config.Metadata.BasePath)
		if err != nil {
			return nil, fmt.Errorf("failed to get absolute path: %w", err)
		}
		config.Metadata.BasePath = absPath
	}

	// Clean the path
	config.Metadata.BasePath = filepath.Clean(config.Metadata.BasePath)

	// Debug log the config
	fmt.Printf("Loaded metadata config with expanded path: %+v\n", config.Metadata)

	return &config, nil
}
