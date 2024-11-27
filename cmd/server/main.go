package main

import (
	"log"
	"os"

	"github.com/ListenUpApp/ListenUp/internal/config"
	"github.com/ListenUpApp/ListenUp/internal/ent"
	logging "github.com/ListenUpApp/ListenUp/internal/logger"
	"github.com/ListenUpApp/ListenUp/internal/repository"
	"github.com/ListenUpApp/ListenUp/internal/server"
	"github.com/ListenUpApp/ListenUp/internal/service"
	"github.com/ListenUpApp/ListenUp/internal/util"
	"github.com/go-playground/validator/v10"
	_ "github.com/mattn/go-sqlite3"
	"golang.org/x/net/context"
)

func initDB() (*ent.Client, error) {
	client, err := ent.Open("sqlite3", "file:listenup.db?cache=shared&_fk=1")
	if err != nil {
		return nil, err
	}

	// Run the auto migration tool
	if err := client.Schema.Create(context.Background()); err != nil {
		return nil, err
	}

	return client, nil
}

func main() {
	cfg, err := config.LoadConfig("./")
	if err != nil {
		log.Fatal("Cannot load configuration:", err)
	}

	appLogger := logging.New(logging.Config{
		Environment: cfg.App.Environment,
		LogLevel:    logging.LogLevel(cfg.Logger.Level),
		Pretty:      true,
	})
	validate := validator.New()
	// todo init this with our DB env variables
	client, err := initDB()
	if err != nil {
		log.Fatal("failed opening connection to sqlite: %v", err)
	}
	defer client.Close()

	if err := util.InitializeAuth(cfg.Cookie); err != nil {
		log.Fatalf("Failed to initialize JWT system: %v", err)
	}

	ctx := context.Background()

	// Init Repos
	repos, err := repository.NewRepositories(repository.Config{
		Client: client,
		Logger: appLogger,
	})

	if err != nil {
		log.Fatal("Failed to initialize repositories", "error", err)
		os.Exit(1)
	}

	// Init Services
	services, err := service.NewServices(service.Deps{
		Repos:     repos,
		Logger:    appLogger,
		Validator: validate,
	})
	if err != nil {
		appLogger.Error("Failed to initialize services", "error", err)
		os.Exit(1)
	}

	srv, err := server.New(server.Config{
		Config:    cfg,
		Logger:    appLogger,
		Services:  services,
		Validator: validate,
	})
	// Check server status and initialize if needed
	if err := srv.Init(ctx); err != nil {
		appLogger.Error("Failed to initialize server", "error", err)
		os.Exit(1)
	}

	// Start the server
	if err := srv.Run(); err != nil {
		appLogger.Error("Server failed", "error", err)
		os.Exit(1)
	}
}
