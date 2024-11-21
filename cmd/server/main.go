package main

import (
	"fmt"
	"github.com/ListenUpApp/ListenUp/internal/api/handler"
	"github.com/ListenUpApp/ListenUp/internal/ent"
	"github.com/ListenUpApp/ListenUp/internal/repository"
	"github.com/ListenUpApp/ListenUp/internal/service"
	"github.com/gin-gonic/gin"
	_ "github.com/mattn/go-sqlite3"
	"golang.org/x/net/context"
	"log"
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
	client, err := initDB()
	if err != nil {
		log.Fatalf("failed opening connection to sqlite: %v", err)
	}
	defer client.Close()

	ctx := context.Background()

	r := gin.Default()
	r.Use(gin.Recovery())
	r.Use(gin.Logger())

	serverRepo := repository.NewServerRepository(client)
	userRepo := repository.NewUserRepository(client)
	authService := service.NewAuthService(userRepo)

	srv, err := serverRepo.GetServer(ctx)

	if err != nil {
		fmt.Errorf("could not check server status")
	}

	if srv == nil {
		_, err := serverRepo.CreateServer(ctx)
		if err != nil {
			fmt.Errorf("could not create server")
		}
	}

	handlers := handler.NewHandler(*serverRepo, *authService)
	handlers.RegisterAppRoutes(r)
	handlers.RegisterApiRoutes(r)

	if err := r.Run(":8080"); err != nil {
		log.Fatal(err)
	}
}
