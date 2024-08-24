package server

import (
	serverv1 "buf.build/gen/go/listenup/listenup/protocolbuffers/go/listenup/server/v1"
	"context"
	"github.com/ListenUpApp/ListenUp/internal/store"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

type ServerHandler struct {
	serverStore store.ServerStore
}

func NewServerHandler(serverStore store.ServerStore) *ServerHandler {
	return &ServerHandler{
		serverStore: serverStore,
	}
}

func (s *ServerHandler) Ping(context.Context, *serverv1.PingRequest) (*serverv1.PingResponse, error) {
	res := &serverv1.PingResponse{
		Message: "Pong",
	}

	return res, nil
}

func (s *ServerHandler) GetServer(ctx context.Context, request *serverv1.GetServerRequest) (*serverv1.GetServerResponse, error) {
	server, err := s.serverStore.GetServer(ctx)
	if err != nil {
		return nil, status.Errorf(codes.NotFound, "could not retrieve server")
	}
	res := &serverv1.GetServerResponse{Server: server.Server}
	return res, nil
}
