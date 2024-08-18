package server

import (
	serverv1 "buf.build/gen/go/listenup/listenup/protocolbuffers/go/listenup/server/v1"
	"connectrpc.com/connect"
	"context"
	"errors"
	"github.com/ListenUpApp/ListenUp/internal/store"
)

type ServerHandler struct {
	serverStore store.ServerStore
}

func NewServerHandler(serverStore store.ServerStore) *ServerHandler {
	return &ServerHandler{
		serverStore: serverStore,
	}
}

func (s *ServerHandler) Ping(context.Context, *connect.Request[serverv1.PingRequest]) (*connect.Response[serverv1.PingResponse], error) {
	res := connect.NewResponse(&serverv1.PingResponse{
		Message: "Pong",
	})

	return res, nil
}

func (s *ServerHandler) GetServer(ctx context.Context, request *connect.Request[serverv1.GetServerRequest]) (*connect.Response[serverv1.GetServerResponse], error) {
	server, err := s.serverStore.GetServer(ctx)
	if err != nil {
		return nil, connect.NewError(connect.CodeNotFound, errors.New("Could not retrieve server"))
	}
	res := connect.NewResponse(&serverv1.GetServerResponse{Server: server.Server})
	return res, nil
}
