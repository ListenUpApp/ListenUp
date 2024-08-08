package internal

import (
	"buf.build/gen/go/listenup/listenup/connectrpc/go/listenup/server/v1/serverv1connect"
	"connectrpc.com/grpcreflect"
	"github.com/ListenUpApp/ListenUp/internal/handlers"
	"github.com/dgraph-io/badger/v4"
	"golang.org/x/net/http2"
	"golang.org/x/net/http2/h2c"
	"net/http"
)

type Server struct {
	db             *badger.DB
	serverHandlers *handlers.ServerHandler
}

func NewServer(db *badger.DB) *Server {
	serverHandlers := handlers.NewServerHandler()

	return &Server{
		db:             db,
		serverHandlers: serverHandlers,
	}
}

func (s Server) StartServer() {
	mux := http.NewServeMux()

	reflector := grpcreflect.NewStaticReflector(
		serverv1connect.ServerServiceName)

	path, handler := serverv1connect.NewServerServiceHandler(s.serverHandlers)
	mux.Handle(path, handler)

	mux.Handle(grpcreflect.NewHandlerV1(reflector))

	println("Starting Server")
	http.ListenAndServe(
		":50051",
		h2c.NewHandler(mux, &http2.Server{}),
	)
}
