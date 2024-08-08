package main

import (
	badger "github.com/ListenUpApp/ListenUp/db"
	"github.com/ListenUpApp/ListenUp/internal"
	"log"
)

func main() {
	db, err := badger.InitDB("./badger-data")

	if err != nil {
		log.Fatalf("Failed to initialize database: %v", err)
	}

	defer badger.CloseDB()

	server := internal.NewServer(db)
	server.StartServer()
}
