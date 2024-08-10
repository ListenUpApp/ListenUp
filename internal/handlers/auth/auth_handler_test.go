package auth

import (
	"context"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
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

func TestLoginUser(t *testing.T) {
	mockUserStore := new(MockUserStore)
	mockAuthStore := new(MockAuthStore)
	handlers := NewAuthHandlers(mockUserStore, mockAuthStore)

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
	mockAuthStore := new(MockAuthStore)
	handlers := NewAuthHandlers(mockUserStore, mockAuthStore)

	ctx := context.Background()
	email := "newuser@example.com"
	name := "New User"
	password := "password123"

	// Mock GetUserByEmail to return a NotFound error
	mockUserStore.On("GetUserByEmail", ctx, email).Return((*authv1.AuthUser)(nil), status.Error(codes.NotFound, "user not found"))

	// Mock CreateUser to return nil error (successful creation)
	mockUserStore.On("CreateUser", ctx, mock.AnythingOfType("*authv1.AuthUser")).Return(nil)

	req := connect.NewRequest(&authv1.RegisterRequest{
		Email:    email,
		Name:     name,
		Password: password,
	})

	resp, err := handlers.RegisterUser(ctx, req)

	assert.NoError(t, err)
	assert.NotNil(t, resp)

	mockUserStore.AssertExpectations(t)
}

func TestRegisterUserExistingEmail(t *testing.T) {
	mockUserStore := new(MockUserStore)
	mockAuthStore := new(MockAuthStore)
	handlers := NewAuthHandlers(mockUserStore, mockAuthStore)

	ctx := context.Background()
	email := "existing@example.com"
	name := "Existing User"
	password := "password123"

	// Mock GetUserByEmail to return an existing user (no error)
	existingUser := &authv1.AuthUser{
		User: &userv1.User{
			Id:    "existinguser123",
			Email: email,
			Name:  name,
		},
	}
	mockUserStore.On("GetUserByEmail", ctx, email).Return(existingUser, nil)

	req := connect.NewRequest(&authv1.RegisterRequest{
		Email:    email,
		Name:     name,
		Password: password,
	})

	resp, err := handlers.RegisterUser(ctx, req)

	assert.Error(t, err)
	assert.Nil(t, resp)

	// Check if the error is a gRPC status error
	statusErr, ok := status.FromError(err)
	assert.True(t, ok, "Expected gRPC status error")
	assert.Equal(t, codes.AlreadyExists, statusErr.Code())
	assert.Contains(t, statusErr.Message(), "already exists")

	mockUserStore.AssertExpectations(t)
}
