package internal

import (
	"buf.build/gen/go/listenup/listenup/connectrpc/go/listenup/auth/v1/authv1connect"
	"buf.build/gen/go/listenup/listenup/connectrpc/go/listenup/server/v1/serverv1connect"
	"connectrpc.com/grpcreflect"
	"github.com/ListenUpApp/ListenUp/internal/db"
	"github.com/ListenUpApp/ListenUp/internal/handlers/auth"
	"github.com/ListenUpApp/ListenUp/internal/handlers/server"
	"github.com/ListenUpApp/ListenUp/internal/store"
	"golang.org/x/net/http2"
	"golang.org/x/net/http2/h2c"
	"net/http"
)

type Server struct {
	db             db.DBInterface // Use DBInterface instead of *badger.DB
	serverHandlers *server.ServerHandler
	authHandlers   *auth.AuthHandlers
}

func NewServer(database db.DBInterface) *Server { // Accept DBInterface
	serverHandlers := server.NewServerHandler()
	authStore := store.NewBadgerAuthStore(database)
	userStore := store.NewBadgerUserStore(database)

	return &Server{
		db:             database,
		serverHandlers: serverHandlers,
		authHandlers:   auth.NewAuthHandlers(userStore, authStore),
	}
}

func (s Server) StartServer() {
	mux := http.NewServeMux()

	reflector := grpcreflect.NewStaticReflector(
		serverv1connect.ServerServiceName)

	path, handler := serverv1connect.NewServerServiceHandler(s.serverHandlers)
	mux.Handle(path, handler)

	authPath, authHandler := authv1connect.NewAuthServiceHandler(s.authHandlers)
	mux.Handle(authPath, authHandler)

	mux.Handle(grpcreflect.NewHandlerV1(reflector))

	println("Starting Server")
	http.ListenAndServe(
		":50051",
		h2c.NewHandler(mux, &http2.Server{}),
	)
}
