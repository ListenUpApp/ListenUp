package main

import (
	"github.com/ListenUpApp/ListenUp/internal"
	"github.com/ListenUpApp/ListenUp/internal/db"
	"github.com/ListenUpApp/ListenUp/internal/logger"
	"log"
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
	server.StartServer()
}
