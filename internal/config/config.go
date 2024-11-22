package config

import (
	"fmt"
	"github.com/spf13/viper"
)

type Config struct {
	App      AppConfig
	Server   ServerConfig
	Database DatabaseConfig
	Logger   LoggerConfig
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

	// Bind environment variables
	viper.BindEnv("App.Environment", "ENV")
	viper.BindEnv("App.Debug", "DEBUG")
	viper.BindEnv("Server.Port", "PORT")
	viper.BindEnv("Database.Name", "DB_NAME")
	viper.BindEnv("Logger.Level", "LOG_LEVEL")

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

	return &config, nil
}
