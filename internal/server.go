package internal

import (
	"buf.build/gen/go/listenup/listenup/grpc/go/listenup/auth/v1/authv1grpc"
	"buf.build/gen/go/listenup/listenup/grpc/go/listenup/server/v1/serverv1grpc"
	"context"
	"fmt"
	"github.com/ListenUpApp/ListenUp/internal/db"
	"github.com/ListenUpApp/ListenUp/internal/handlers/auth"
	"github.com/ListenUpApp/ListenUp/internal/handlers/server"
	"github.com/ListenUpApp/ListenUp/internal/logger"
	"github.com/ListenUpApp/ListenUp/internal/store"
	"google.golang.org/grpc"
	"net"
)

type Server struct {
	db             db.DBInterface
	serverHandlers *server.ServerHandler
	authHandlers   *auth.AuthHandlers
	grpcServer     *grpc.Server
	listener       net.Listener
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

func (s *Server) StartServer() error {
	lis, err := net.Listen("tcp", ":50051") // Or whatever port you want to use
	if err != nil {
		return fmt.Errorf("failed to listen: %v", err)
	}
	s.listener = lis

	s.grpcServer = grpc.NewServer()
	serverv1grpc.RegisterServerServiceServer(s.grpcServer, s.serverHandlers)
	authv1grpc.RegisterAuthServiceServer(s.grpcServer, s.authHandlers)

	logger.Info("Starting Server", "address", lis.Addr().String())

	return s.grpcServer.Serve(lis)
}

func (s *Server) Shutdown(ctx context.Context) error {
	done := make(chan struct{})
	go func() {
		s.grpcServer.GracefulStop()
		close(done)
	}()

	select {
	case <-ctx.Done():
		// Force stop if graceful stop didn't finish in time
		s.grpcServer.Stop()
		return ctx.Err()
	case <-done:
		return nil
	}
}
