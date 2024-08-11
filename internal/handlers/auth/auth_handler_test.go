package auth

import (
	serverv1 "buf.build/gen/go/listenup/listenup/protocolbuffers/go/listenup/server/v1"
	"context"
	"errors"
	"testing"
	"time"

	"buf.build/gen/go/listenup/listenup/protocolbuffers/go/listenup/auth/v1"
	"buf.build/gen/go/listenup/listenup/protocolbuffers/go/listenup/user/v1"
	"connectrpc.com/connect"
	"github.com/ListenUpApp/ListenUp/internal/utils"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"
)

type MockUserStore struct {
	mock.Mock
}

func (m *MockUserStore) GetUserByEmail(ctx context.Context, email string) (*authv1.AuthUser, error) {
	args := m.Called(ctx, email)
	return args.Get(0).(*authv1.AuthUser), args.Error(1)
}

func (m *MockUserStore) GetUserById(ctx context.Context, id string) (*authv1.AuthUser, error) {
	args := m.Called(ctx, id)
	return args.Get(0).(*authv1.AuthUser), args.Error(1)
}

func (m *MockUserStore) CreateUser(ctx context.Context, user *authv1.AuthUser) error {
	args := m.Called(ctx, user)
	return args.Error(0)
}

type MockAuthStore struct {
	mock.Mock
}

func (m *MockAuthStore) StoreRefreshToken(ctx context.Context, userID, token string) error {
	args := m.Called(ctx, userID, token)
	return args.Error(0)
}

func (m *MockAuthStore) GetRefreshToken(ctx context.Context, userID string) (string, error) {
	args := m.Called(ctx, userID)
	return args.String(0), args.Error(1)
}

func (m *MockAuthStore) UpdateRefreshToken(ctx context.Context, userID, token string) error {
	args := m.Called(ctx, userID, token)
	return args.Error(0)
}
func (m *MockAuthStore) DeleteRefreshToken(ctx context.Context, userID string) error {
	args := m.Called(ctx, userID)
	return args.Error(0)
}

func (m *MockAuthStore) CleanupExpiredTokens(ctx context.Context, expirationTime time.Duration) error {
	args := m.Called(ctx, expirationTime)
	return args.Error(0)
}

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

func TestLoginUser(t *testing.T) {
	mockUserStore := new(MockUserStore)
	mockAuthStore := new(MockAuthStore)
	mockServerStore := new(MockServerStore)
	handlers := NewAuthHandlers(mockUserStore, mockAuthStore, mockServerStore)

	ctx := context.Background()
	email := "test@example.com"
	password := "password123"

	hashedPassword, _ := utils.HashPassword(password)
	mockUser := &authv1.AuthUser{
		HashedPassword: hashedPassword,
		User: &userv1.User{
			Id:    "user123",
			Email: email,
			Role:  0,
		},
	}

	mockUserStore.On("GetUserByEmail", ctx, email).Return(mockUser, nil)
	mockAuthStore.On("StoreRefreshToken", ctx, mock.Anything, mock.Anything).Return(nil)

	req := connect.NewRequest(&authv1.LoginRequest{
		Email:    email,
		Password: password,
	})

	resp, err := handlers.LoginUser(ctx, req)

	assert.NoError(t, err)
	assert.NotNil(t, resp)
	assert.NotEmpty(t, resp.Msg.AccessToken)
	assert.NotEmpty(t, resp.Msg.RefreshToken)
	assert.Equal(t, mockUser.User, resp.Msg.User)

	mockUserStore.AssertExpectations(t)
	mockAuthStore.AssertExpectations(t)
}

func TestRegisterUser(t *testing.T) {
	mockUserStore := new(MockUserStore)
	mockServerStore := new(MockServerStore)

	h := &AuthHandlers{
		userStore:   mockUserStore,
		serverStore: mockServerStore,
	}

	ctx := context.Background()

	t.Run("Successful registration - first user (root)", func(t *testing.T) {
		req := connect.NewRequest(&authv1.RegisterRequest{
			Name:     "Test User",
			Email:    "test@example.com",
			Password: "password123",
		})

		server := &authv1.AuthServer{
			Server: &serverv1.Server{
				IsSetUp: false,
			},
		}

		mockUserStore.On("GetUserByEmail", ctx, "test@example.com").Return((*authv1.AuthUser)(nil), errors.New("user not found"))
		mockServerStore.On("GetServer", ctx).Return(server, nil)
		mockUserStore.On("CreateUser", ctx, mock.AnythingOfType("*authv1.AuthUser")).Return(nil)
		mockServerStore.On("UpdateServer", ctx, mock.AnythingOfType("func(*authv1.AuthServer) error")).
			Run(func(args mock.Arguments) {
				updateFunc := args.Get(1).(func(*authv1.AuthServer) error)
				updateFunc(server)
			}).
			Return(nil)

		resp, err := h.RegisterUser(ctx, req)

		assert.NoError(t, err)
		assert.NotNil(t, resp)
		assert.True(t, server.Server.IsSetUp, "Server should be marked as set up")
		mockUserStore.AssertExpectations(t)
		mockServerStore.AssertExpectations(t)
	})

	t.Run("User already exists", func(t *testing.T) {
		req := connect.NewRequest(&authv1.RegisterRequest{
			Name:     "Existing User",
			Email:    "existing@example.com",
			Password: "password123",
		})

		mockUserStore.On("GetUserByEmail", ctx, "existing@example.com").Return(&authv1.AuthUser{}, nil)

		resp, err := h.RegisterUser(ctx, req)

		assert.Error(t, err)
		assert.Nil(t, resp)
		connectErr, ok := err.(*connect.Error)
		assert.True(t, ok)
		assert.Equal(t, connect.CodeAlreadyExists, connectErr.Code())
		assert.Contains(t, connectErr.Message(), "A User with that username already exists")
		mockUserStore.AssertExpectations(t)
	})

	t.Run("Server retrieval error", func(t *testing.T) {
		req := connect.NewRequest(&authv1.RegisterRequest{
			Name:     "Error User",
			Email:    "error@example.com",
			Password: "password101",
		})

		mockUserStore.On("GetUserByEmail", ctx, "error@example.com").Return((*authv1.AuthUser)(nil), errors.New("user not found"))
		mockServerStore.On("GetServer", ctx).Return((*authv1.AuthServer)(nil), errors.New("server error"))

		resp, err := h.RegisterUser(ctx, req)

		assert.Error(t, err, "Expected an error, but got nil")
		assert.Nil(t, resp, "Expected nil response, but got: %+v", resp)

		connectErr, ok := err.(*connect.Error)
		assert.True(t, ok, "Expected a *connect.Error, but got: %T", err)
		if ok {
			assert.Equal(t, connect.CodeInternal, connectErr.Code(), "Expected CodeInternal, but got: %v", connectErr.Code())
			assert.Contains(t, connectErr.Message(), "Could not retrieve server", "Error message does not contain expected text")
		}

		mockUserStore.AssertExpectations(t)
		mockServerStore.AssertExpectations(t)
		mockUserStore.AssertNotCalled(t, "CreateUser")
	})
	t.Run("Server is nil", func(t *testing.T) {
		req := connect.NewRequest(&authv1.RegisterRequest{
			Name:     "Nil Server User",
			Email:    "nil_server@example.com",
			Password: "password404",
		})

		mockUserStore.On("GetUserByEmail", ctx, "nil_server@example.com").Return((*authv1.AuthUser)(nil), errors.New("user not found"))
		mockServerStore.On("GetServer", ctx).Return((*authv1.AuthServer)(nil), nil)

		resp, err := h.RegisterUser(ctx, req)

		assert.Error(t, err, "Expected an error, but got nil")
		assert.Nil(t, resp, "Expected nil response, but got: %+v", resp)

		connectErr, ok := err.(*connect.Error)
		assert.True(t, ok, "Expected a *connect.Error, but got: %T", err)
		if ok {
			assert.Equal(t, connect.CodeInternal, connectErr.Code(), "Expected CodeInternal, but got: %v", connectErr.Code())
			assert.Contains(t, connectErr.Message(), "Invalid server state", "Error message does not contain expected text")
		}

		mockUserStore.AssertExpectations(t)
		mockServerStore.AssertExpectations(t)
		mockUserStore.AssertNotCalled(t, "CreateUser")
	})

	t.Run("User creation error", func(t *testing.T) {
		req := connect.NewRequest(&authv1.RegisterRequest{
			Name:     "Failed User",
			Email:    "failed@example.com",
			Password: "password202",
		})

		mockUserStore.On("GetUserByEmail", ctx, "failed@example.com").Return((*authv1.AuthUser)(nil), errors.New("user not found"))
		mockServerStore.On("GetServer", ctx).Return(&authv1.AuthServer{
			Server: &serverv1.Server{
				IsSetUp: true,
			},
		}, nil)
		mockUserStore.On("CreateUser", ctx, mock.AnythingOfType("*authv1.AuthUser")).Return(errors.New("creation error"))

		resp, err := h.RegisterUser(ctx, req)

		assert.Error(t, err)
		assert.Nil(t, resp)
		connectErr, ok := err.(*connect.Error)
		assert.True(t, ok)
		assert.Equal(t, connect.CodeInternal, connectErr.Code())
		assert.Contains(t, connectErr.Message(), "Unable to save user to Database")
		mockUserStore.AssertExpectations(t)
		mockServerStore.AssertExpectations(t)
	})

	t.Run("Server update error", func(t *testing.T) {
		req := connect.NewRequest(&authv1.RegisterRequest{
			Name:     "Update Error User",
			Email:    "update_error@example.com",
			Password: "password303",
		})

		mockUserStore.On("GetUserByEmail", ctx, "update_error@example.com").Return((*authv1.AuthUser)(nil), errors.New("user not found"))
		mockServerStore.On("GetServer", ctx).Return(&authv1.AuthServer{
			Server: &serverv1.Server{
				IsSetUp: false,
			},
		}, nil)
		mockUserStore.On("CreateUser", ctx, mock.AnythingOfType("*authv1.AuthUser")).Return(nil)
		mockServerStore.On("UpdateServer", ctx, mock.AnythingOfType("func(*authv1.AuthServer) error")).Return(errors.New("update error"))

		resp, err := h.RegisterUser(ctx, req)

		assert.Error(t, err)
		assert.Nil(t, resp)
		connectErr, ok := err.(*connect.Error)
		assert.True(t, ok)
		assert.Equal(t, connect.CodeInternal, connectErr.Code())
		assert.Contains(t, connectErr.Message(), "Could not update server setup status")
		mockUserStore.AssertExpectations(t)
		mockServerStore.AssertExpectations(t)
	})
}
