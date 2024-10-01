package auth

import (
	authv1 "buf.build/gen/go/listenup/listenup/protocolbuffers/go/listenup/auth/v1"
	permissionv1 "buf.build/gen/go/listenup/listenup/protocolbuffers/go/listenup/permission/v1"
	userv1 "buf.build/gen/go/listenup/listenup/protocolbuffers/go/listenup/user/v1"
	"context"
	"github.com/ListenUpApp/ListenUp/internal/logger"
	"github.com/ListenUpApp/ListenUp/internal/store"
	"github.com/ListenUpApp/ListenUp/internal/utils"
	gonanoid "github.com/matoous/go-nanoid/v2"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
	"google.golang.org/protobuf/types/known/timestamppb"
	"time"
)

const (
	ACCESS_TTL  = 162 * time.Hour
	REFRESH_TTL = (14 * 24) * time.Hour
)

type AuthHandlers struct {
	userStore   store.UserStore
	authStore   store.AuthStore
	serverStore store.ServerStore
}

func NewAuthHandlers(userStore store.UserStore, authStore store.AuthStore, serverStore store.ServerStore) *AuthHandlers {
	return &AuthHandlers{
		userStore:   userStore,
		authStore:   authStore,
		serverStore: serverStore,
	}
}

func (h *AuthHandlers) Login(ctx context.Context, req *authv1.LoginRequest) (*authv1.LoginResponse, error) {
	//violations := validator.ValidateLoginRequest(req)
	//if violations != nil {
	//	return nil, status.Errorf(codes.InvalidArgument, "Invalid login request: %v", violations)
	//}

	user, err := h.userStore.GetUserByEmail(ctx, req.GetEmail())
	if err != nil {
		logger.Error("Could Not find User", "email", req.GetEmail())
		return nil, status.Errorf(codes.NotFound, "user not found")
	}

	if utils.CheckPasswordHash(req.GetPassword(), user.HashedPassword) == false {
		logger.Error("Could Not find User", "email", req.GetEmail())
		return nil, status.Errorf(codes.InvalidArgument, "incorrect password")
	}

	// Generate access token
	accessToken, err := utils.GenerateToken(user.User.Id, int32(user.User.Role), user.User.Email, ACCESS_TTL)
	if err != nil {
		logger.Error("Could not generate access token", "Error", err)
		return nil, status.Errorf(codes.Internal, "could not generate access token")
	}

	// Generate refresh token
	refreshToken, err := utils.GenerateToken(user.User.Id, int32(user.User.Role), user.User.Email, REFRESH_TTL)
	if err != nil {
		logger.Error("Could not generate refresh token", "Error", err)
		return nil, status.Errorf(codes.Internal, "could not generate refresh token")
	}

	err = h.authStore.StoreRefreshToken(ctx, user.User.Id, refreshToken)
	if err != nil {
		logger.Error("Could not generate access token", "Error", err)
		return nil, status.Errorf(codes.Internal, "could not store refresh token")
	}

	res := &authv1.LoginResponse{
		AccessToken:  accessToken,
		RefreshToken: refreshToken,
		User:         user.User,
	}

	return res, nil
}

func (h *AuthHandlers) Register(ctx context.Context, req *authv1.RegisterRequest) (*authv1.RegisterResponse, error) {
	//violations := validator.ValidateRegisterRequest(req)
	//if len(violations) > 0 {
	//	return nil, connect.NewError(connect.CodeInvalidArgument, common.InvalidArgumentError(violations))
	//}

	// Check if the user already exists.
	_, err := h.userStore.GetUserByEmail(ctx, req.GetEmail())
	if err == nil {
		return nil, status.Errorf(codes.Unauthenticated, "A User with that username already exists")
	}

	server, err := h.serverStore.GetServer(ctx)

	if err != nil {
		logger.Error("Unable to retrieve an instance of the server.")
		return nil, status.Errorf(codes.Internal, "could not retrieve server")
	}
	if server == nil || server.Server == nil {
		logger.Error("Server or Server.Server is nil.")
		return nil, status.Errorf(codes.Internal, "invalid server state")
	}

	var role = permissionv1.Role_ROLE_UNSPECIFIED
	if !server.Server.IsSetUp {
		role = permissionv1.Role_ROLE_ROOT
	} else {
		role = permissionv1.Role_ROLE_USER
	}

	id, err := gonanoid.Generate("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz", 8)
	if err != nil {
		logger.Error("Could not generate ID")
		return nil, status.Errorf(codes.Internal, "could not generate user id")
	}

	hashedPassword, err := utils.HashPassword(req.GetPassword())
	if err != nil {
		return nil, status.Errorf(codes.Internal, "failed to hash password")
	}
	newUser := userv1.User{
		Id:                  id,
		Name:                req.GetName(),
		Email:               req.GetEmail(),
		CreatedAt:           timestamppb.Now(),
		LastLogin:           timestamppb.Now(),
		Role:                role,
		OverridePermissions: nil,
	}

	createUserParams := authv1.AuthUser{
		HashedPassword: hashedPassword,
		User:           &newUser,
	}
	err = h.userStore.CreateUser(ctx, &createUserParams)
	if err != nil {
		logger.Error("Could not Save user to the database", "Email", req.GetEmail())
		return nil, status.Errorf(codes.Internal, "unable to save user in database")
	}

	if !server.Server.IsSetUp {
		err := h.serverStore.UpdateServer(ctx, func(server *authv1.AuthServer) error {
			server.Server.IsSetUp = true
			return nil
		})
		if err != nil {
			logger.Error("Could not update server setup status.")
			return nil, status.Errorf(codes.Internal, "could not update server status")
		}
	}

	res := &authv1.RegisterResponse{}
	return res, nil
}

func (h *AuthHandlers) RefreshToken(ctx context.Context, req *authv1.RefreshTokenRequest) (*authv1.RefreshTokenResponse, error) {
	claims, err := utils.ParseToken(req.GetRefreshToken())
	if err != nil {
		return nil, status.Errorf(codes.Unauthenticated, "invalid refresh token")
	}

	userID, ok := claims["user_id"].(string)
	if !ok {
		logger.Error("Invalid Claim Format")
		return nil, status.Errorf(codes.Internal, "invalid claim format")
	}

	// Check if the refresh token exists in the database
	storedToken, err := h.authStore.GetRefreshToken(ctx, userID)
	if err != nil {
		logger.Error("Could not retrieve refresh token")
		return nil, status.Errorf(codes.Internal, "could not retrieve refresh token")
	}

	if storedToken != req.GetRefreshToken() {
		logger.Warn("Refresh token has been revoked")
		return nil, status.Errorf(codes.Unauthenticated, "refresh token has been revoked")
	}

	user, err := h.userStore.GetUserById(ctx, userID)
	if err != nil {
		logger.Warn("Could not retrieve user")
		return nil, status.Errorf(codes.Internal, "could not retrieve user")
	}

	newAccessToken, err := utils.GenerateToken(user.User.Id, int32(user.User.Role), user.User.Email, ACCESS_TTL)
	if err != nil {
		logger.Error("Could not generate new access token")
		return nil, status.Errorf(codes.Internal, "could not generate new access token")
	}

	newRefreshToken, err := utils.GenerateToken(user.User.Id, int32(user.User.Role), user.User.Email, REFRESH_TTL)
	if err != nil {
		logger.Error("Could not generate new refresh token")
		return nil, status.Errorf(codes.Internal, "could not generate new refresh token")
	}

	// Update refresh token in the database
	err = h.authStore.UpdateRefreshToken(ctx, user.User.Id, newRefreshToken)
	if err != nil {
		logger.Error("Could not update refresh token")
		return nil, status.Errorf(codes.Internal, "could not update refresh token")
	}

	// Create response
	res := &authv1.RefreshTokenResponse{
		AccessToken: newAccessToken,
	}

	return res, nil
}
