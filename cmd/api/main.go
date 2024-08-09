package main

import (
	"github.com/ListenUpApp/ListenUp/db"
	"github.com/ListenUpApp/ListenUp/internal"
	"log"
)

func main() {
	database, err := db.InitDB("./badger-data", nil)

	if err != nil {
		log.Fatalf("Failed to initialize database: %v", err)
	}

	defer db.CloseDB()

	server := internal.NewServer(database)
	server.StartServer()
}
