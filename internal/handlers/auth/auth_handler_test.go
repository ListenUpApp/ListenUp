package auth

import (
	"buf.build/gen/go/listenup/listenup/protocolbuffers/go/listenup/auth/v1"
	permissionv1 "buf.build/gen/go/listenup/listenup/protocolbuffers/go/listenup/permission/v1"
	serverv1 "buf.build/gen/go/listenup/listenup/protocolbuffers/go/listenup/server/v1"
	"buf.build/gen/go/listenup/listenup/protocolbuffers/go/listenup/user/v1"
	"connectrpc.com/connect"
	"context"
	"errors"
	"github.com/ListenUpApp/ListenUp/internal/utils"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"
	"github.com/stretchr/testify/suite"
	"testing"
	"time"
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

// MockAuthStore implements the AuthStore interface for testing
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

func (m *MockAuthStore) UpdateRefreshToken(ctx context.Context, userID, newToken string) error {
	args := m.Called(ctx, userID, newToken)
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

// MockServerStore implements the ServerStore interface for testing
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
	args := m.Called(ctx, mock.AnythingOfType("func(*authv1.AuthServer) error"))
	return args.Error(0)
}

// Helper function to simulate the behavior of UpdateServer
func (m *MockServerStore) CallUpdateServer(ctx context.Context, updateFunc func(*authv1.AuthServer) error) error {
	server := &authv1.AuthServer{}
	err := updateFunc(server)
	if err != nil {
		return err
	}
	return m.UpdateServer(ctx, updateFunc)
}

// ErrTokenNotFound is used to simulate the token not found error
var ErrTokenNotFound = errors.New("token not found")

type AuthHandlersTestSuite struct {
	suite.Suite
	handlers   *AuthHandlers
	mockUser   *MockUserStore
	mockAuth   *MockAuthStore
	mockServer *MockServerStore
}

func (suite *AuthHandlersTestSuite) SetupTest() {
	suite.mockUser = new(MockUserStore)
	suite.mockAuth = new(MockAuthStore)
	suite.mockServer = new(MockServerStore)
	suite.handlers = NewAuthHandlers(suite.mockUser, suite.mockAuth, suite.mockServer)
}

func (suite *AuthHandlersTestSuite) TestLoginUser() {
	ctx := context.Background()
	email := "test@example.com"
	password := "password123"
	hashedPassword, _ := utils.HashPassword(password)

	user := &authv1.AuthUser{
		User: &userv1.User{
			Id:    "user123",
			Email: email,
			Role:  permissionv1.Role_ROLE_USER,
		},
		HashedPassword: hashedPassword,
	}

	suite.mockUser.On("GetUserByEmail", ctx, email).Return(user, nil)
	suite.mockAuth.On("StoreRefreshToken", ctx, user.User.Id, mock.Anything).Return(nil)

	req := connect.NewRequest(&authv1.LoginRequest{
		Email:    email,
		Password: password,
	})

	resp, err := suite.handlers.LoginUser(ctx, req)

	assert.NoError(suite.T(), err)
	assert.NotNil(suite.T(), resp)
	assert.NotEmpty(suite.T(), resp.Msg.AccessToken)
	assert.NotEmpty(suite.T(), resp.Msg.RefreshToken)
	assert.Equal(suite.T(), user.User, resp.Msg.User)

	suite.mockUser.AssertExpectations(suite.T())
	suite.mockAuth.AssertExpectations(suite.T())
}

func (suite *AuthHandlersTestSuite) TestRegisterUser() {
	ctx := context.Background()
	email := "newuser@example.com"
	password := "newpassword123"
	name := "New User"

	suite.mockUser.On("GetUserByEmail", ctx, email).Return((*authv1.AuthUser)(nil), assert.AnError)
	suite.mockServer.On("GetServer", ctx).Return(&authv1.AuthServer{
		Server: &serverv1.Server{
			IsSetUp: false,
		},
	}, nil)
	suite.mockUser.On("CreateUser", ctx, mock.Anything).Return(nil)
	suite.mockServer.On("UpdateServer", ctx, mock.Anything).Return(nil)

	req := connect.NewRequest(&authv1.RegisterRequest{
		Email:    email,
		Password: password,
		Name:     name,
	})

	resp, err := suite.handlers.RegisterUser(ctx, req)

	assert.NoError(suite.T(), err)
	assert.NotNil(suite.T(), resp)

	suite.mockUser.AssertExpectations(suite.T())
	suite.mockServer.AssertExpectations(suite.T())
}

func (suite *AuthHandlersTestSuite) TestRefreshToken() {
	ctx := context.Background()
	userID := "user123"
	email := "test@example.com"
	role := permissionv1.Role_ROLE_USER

	oldRefreshToken, _ := utils.GenerateToken(userID, int32(role), email, REFRESH_TTL)

	user := &authv1.AuthUser{
		User: &userv1.User{
			Id:    userID,
			Email: email,
			Role:  role,
		},
	}

	suite.mockAuth.On("GetRefreshToken", ctx, userID).Return(oldRefreshToken, nil)
	suite.mockUser.On("GetUserById", ctx, userID).Return(user, nil)
	suite.mockAuth.On("UpdateRefreshToken", ctx, userID, mock.Anything).Return(nil)

	req := connect.NewRequest(&authv1.RefreshTokenRequest{
		RefreshToken: oldRefreshToken,
	})

	resp, err := suite.handlers.RefreshToken(ctx, req)

	assert.NoError(suite.T(), err)
	assert.NotNil(suite.T(), resp)
	assert.NotEmpty(suite.T(), resp.Msg.AccessToken)

	suite.mockAuth.AssertExpectations(suite.T())
	suite.mockUser.AssertExpectations(suite.T())
}

func TestAuthHandlersSuite(t *testing.T) {
	suite.Run(t, new(AuthHandlersTestSuite))
}
