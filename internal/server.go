package internal

import (
	"buf.build/gen/go/listenup/listenup/grpc/go/listenup/auth/v1/authv1grpc"
	"buf.build/gen/go/listenup/listenup/grpc/go/listenup/server/v1/serverv1grpc"
	"context"
	"github.com/ListenUpApp/ListenUp/internal/db"
	"github.com/ListenUpApp/ListenUp/internal/handlers/auth"
	"github.com/ListenUpApp/ListenUp/internal/handlers/server"
	"github.com/ListenUpApp/ListenUp/internal/logger"
	"github.com/ListenUpApp/ListenUp/internal/store"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
)

type Server struct {
	db             db.DBInterface
	serverHandlers *server.ServerHandler
	authHandlers   *auth.AuthHandlers
	grpcServer     *grpc.Server
}

func NewServer(database db.DBInterface) *Server {
	ctx := context.Background()

	serverStore := store.NewBadgerServerStore(database)
	authStore := store.NewBadgerAuthStore(database)
	userStore := store.NewBadgerUserStore(database)

	serverHandlers := server.NewServerHandler(serverStore)
	result, _ := serverStore.GetServer(ctx)
	if result == nil {
		// We don't have a server, so create a new one.
		logger.Info("Setting up server for the first time")
		err := serverStore.CreateServer(ctx)
		if err != nil {
			logger.Error("Could not create a new server record.")
		}
	}

	return &Server{
		db:             database,
		serverHandlers: serverHandlers,
		authHandlers:   auth.NewAuthHandlers(userStore, authStore, serverStore),
	}
}

func (s Server) StartServer() {

	s.grpcServer = grpc.NewServer(grpc.Creds(insecure.NewCredentials()))

	serverv1grpc.RegisterServerServiceServer(s.grpcServer, s.serverHandlers)

	authv1grpc.RegisterAuthServiceServer(s.grpcServer, s.authHandlers)

	logger.Info("Starting Server")

	logger.Info("address", "gRPC Server listening")
}
