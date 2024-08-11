package internal

import (
	"buf.build/gen/go/listenup/listenup/connectrpc/go/listenup/auth/v1/authv1connect"
	"buf.build/gen/go/listenup/listenup/connectrpc/go/listenup/server/v1/serverv1connect"
	"connectrpc.com/connect"
	"connectrpc.com/grpcreflect"
	"context"
	"github.com/ListenUpApp/ListenUp/internal/db"
	"github.com/ListenUpApp/ListenUp/internal/handlers/auth"
	"github.com/ListenUpApp/ListenUp/internal/handlers/server"
	"github.com/ListenUpApp/ListenUp/internal/logger"
	"github.com/ListenUpApp/ListenUp/internal/middleware"
	"github.com/ListenUpApp/ListenUp/internal/store"
	"golang.org/x/net/http2"
	"golang.org/x/net/http2/h2c"
	"net/http"
)

type Server struct {
	db             db.DBInterface
	serverHandlers *server.ServerHandler
	authHandlers   *auth.AuthHandlers
}

func NewServer(database db.DBInterface) *Server {
	ctx := context.Background()
	serverHandlers := server.NewServerHandler()

	serverStore := store.NewBadgerServerStore(database)
	authStore := store.NewBadgerAuthStore(database)
	userStore := store.NewBadgerUserStore(database)

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

	mux := http.NewServeMux()

	reflector := grpcreflect.NewStaticReflector(
		serverv1connect.ServerServiceName)

	interceptor := connect.WithInterceptors(middleware.LoggingInterceptor())

	path, handler := serverv1connect.NewServerServiceHandler(s.serverHandlers, interceptor)
	mux.Handle(path, handler)

	authPath, authHandler := authv1connect.NewAuthServiceHandler(s.authHandlers, interceptor)
	mux.Handle(authPath, authHandler)

	mux.Handle(grpcreflect.NewHandlerV1(reflector))

	logger.Info("Starting Server")
	http.ListenAndServe(
		":50051",
		h2c.NewHandler(mux, &http2.Server{}),
	)
}
