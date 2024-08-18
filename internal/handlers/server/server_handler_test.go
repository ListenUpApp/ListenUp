package server

import (
	authv1 "buf.build/gen/go/listenup/listenup/protocolbuffers/go/listenup/auth/v1"
	serverv1 "buf.build/gen/go/listenup/listenup/protocolbuffers/go/listenup/server/v1"
	"connectrpc.com/connect"
	"context"
	"errors"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"
	"testing"
)

type MockServerStore struct {
	mock.Mock
}

func (m *MockServerStore) GetServer(ctx context.Context) (*authv1.AuthServer, error) {
	args := m.Called(ctx)
	return args.Get(0).(*authv1.AuthServer), args.Error(1)
}

func (m *MockServerStore) CreateServer(ctx context.Context) error {
	args := m.Called(ctx)
	return args.Error(0)
}

func (m *MockServerStore) UpdateServer(ctx context.Context, updateFunc func(*authv1.AuthServer) error) error {
	args := m.Called(ctx, updateFunc)
	return args.Error(0)
}

func TestNewServerHandler(t *testing.T) {
	mockStore := new(MockServerStore)
	handler := NewServerHandler(mockStore)

	assert.NotNil(t, handler)
	assert.Equal(t, mockStore, handler.serverStore)
}

func TestServerPing(t *testing.T) {
	handler := NewServerHandler(nil) // serverStore not needed for Ping

	req := connect.NewRequest(&serverv1.PingRequest{})
	resp, err := handler.Ping(context.Background(), req)

	assert.NoError(t, err)
	assert.NotNil(t, resp)
	assert.Equal(t, "Pong", resp.Msg.Message)
}

func TestGetServerSuccess(t *testing.T) {
	mockStore := new(MockServerStore)
	handler := NewServerHandler(mockStore)

	expectedAuthServer := &authv1.AuthServer{
		Server: &serverv1.Server{
			IsSetUp: false,
			Config:  nil,
		},
		JwtSigningToken: "signing-key",
	}

	mockStore.On("GetServer", mock.Anything).Return(expectedAuthServer, nil)

	req := connect.NewRequest(&serverv1.GetServerRequest{})
	resp, err := handler.GetServer(context.Background(), req)

	assert.NoError(t, err)
	assert.NotNil(t, resp)
	assert.Equal(t, expectedAuthServer.Server.IsSetUp, resp.Msg.Server.IsSetUp)
	assert.Equal(t, expectedAuthServer.Server.Config, resp.Msg.Server.Config)

	mockStore.AssertExpectations(t)
}

func TestGetServerError(t *testing.T) {
	mockStore := new(MockServerStore)
	handler := NewServerHandler(mockStore)

	mockStore.On("GetServer", mock.Anything).Return((*authv1.AuthServer)(nil), errors.New("database error"))

	req := connect.NewRequest(&serverv1.GetServerRequest{})
	resp, err := handler.GetServer(context.Background(), req)

	assert.Error(t, err)
	assert.Nil(t, resp)

	connectErr, ok := err.(*connect.Error)
	assert.True(t, ok)
	assert.Equal(t, connect.CodeNotFound, connectErr.Code())
	assert.Equal(t, "Could not retrieve server", connectErr.Message())

	mockStore.AssertExpectations(t)
}
