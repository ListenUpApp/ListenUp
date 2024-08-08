package handlers

import (
	serverv1 "buf.build/gen/go/listenup/listenup/protocolbuffers/go/listenup/server/v1"
	"connectrpc.com/connect"
	"context"
)

type ServerHandler struct {
}

func NewServerHandler() *ServerHandler {
	return &ServerHandler{}
}

func (s *ServerHandler) Ping(context.Context, *connect.Request[serverv1.PingRequest]) (*connect.Response[serverv1.PingResponse], error) {
	res := connect.NewResponse(&serverv1.PingResponse{
		Message: "Pong",
	})

	return res, nil
}
