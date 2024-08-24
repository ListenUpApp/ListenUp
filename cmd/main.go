package main

import (
	"context"
	"github.com/ListenUpApp/ListenUp/internal"
	"github.com/ListenUpApp/ListenUp/internal/db"
	"github.com/ListenUpApp/ListenUp/internal/logger"
	"log"
	"os"
	"os/signal"
	"syscall"
	"time"
)

func main() {
	defer logger.Sync()
	logger.Info("Application started")
	database, err := db.InitDB("./badger-data", nil)

	if err != nil {
		logger.Error("Database could not be created")
		log.Fatalf("Failed to initialize database: %v", err)
	}

	defer db.CloseDB()

	server := internal.NewServer(database)

	// Start server in a goroutine
	go func() {
		if err := server.StartServer(); err != nil {
			logger.Error("Failed to start server", "error", err)
			os.Exit(1)
		}
	}()

	// Wait for interrupt signal to gracefully shut down the server
	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	logger.Info("Shutting down server...")

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	if err := server.Shutdown(ctx); err != nil {
		logger.Error("Server forced to shutdown", "error", err)
	}

	logger.Info("Server exiting")
}
