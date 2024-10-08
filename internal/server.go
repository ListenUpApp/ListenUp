package internal

import (
	"buf.build/gen/go/listenup/listenup/grpc/go/listenup/auth/v1/authv1grpc"
	"buf.build/gen/go/listenup/listenup/grpc/go/listenup/folder/v1/folderv1grpc"
	"buf.build/gen/go/listenup/listenup/grpc/go/listenup/library/v1/libraryv1grpc"
	"buf.build/gen/go/listenup/listenup/grpc/go/listenup/server/v1/serverv1grpc"
	"context"
	"fmt"
	"github.com/ListenUpApp/ListenUp/internal/db"
	"github.com/ListenUpApp/ListenUp/internal/handlers/auth"
	"github.com/ListenUpApp/ListenUp/internal/handlers/folder"
	"github.com/ListenUpApp/ListenUp/internal/handlers/library"
	"github.com/ListenUpApp/ListenUp/internal/handlers/server"
	"github.com/ListenUpApp/ListenUp/internal/logger"
	"github.com/ListenUpApp/ListenUp/internal/middleware"
	"github.com/ListenUpApp/ListenUp/internal/store"
	"google.golang.org/grpc"
	"net"
)

type Server struct {
	db              db.DBInterface
	serverHandlers  *server.ServerHandler
	authHandlers    *auth.AuthHandlers
	libraryHandlers *library.LibraryHandler
	folderHandlers  *folder.FolderHandler
	grpcServer      *grpc.Server
	listener        net.Listener
}

func NewServer(database db.DBInterface) *Server {
	ctx := context.Background()

	serverStore := store.NewBadgerServerStore(database)
	authStore := store.NewBadgerAuthStore(database)
	userStore := store.NewBadgerUserStore(database)
	libraryStore := store.NewBadgerLibraryStore(database)

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
		db:              database,
		serverHandlers:  serverHandlers,
		authHandlers:    auth.NewAuthHandlers(userStore, authStore, serverStore),
		libraryHandlers: library.NewLibraryHandler(libraryStore, userStore),
		folderHandlers:  folder.NewFoldersHandler(),
	}
}

func (s *Server) StartServer() error {
	lis, err := net.Listen("tcp", ":50051") // Or whatever port you want to use
	if err != nil {
		return fmt.Errorf("failed to listen: %v", err)
	}
	s.listener = lis

	s.grpcServer = grpc.NewServer(grpc.UnaryInterceptor(middleware.AuthInterceptor))
	serverv1grpc.RegisterServerServiceServer(s.grpcServer, s.serverHandlers)
	authv1grpc.RegisterAuthServiceServer(s.grpcServer, s.authHandlers)
	libraryv1grpc.RegisterLibraryServiceServer(s.grpcServer, s.libraryHandlers)
	folderv1grpc.RegisterFolderServiceServer(s.grpcServer, s.folderHandlers)

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
