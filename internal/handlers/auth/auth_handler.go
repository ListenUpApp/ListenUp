package auth

import (
	"buf.build/gen/go/listenup/listenup/connectrpc/go/listenup/auth/v1/authv1connect"
	authv1 "buf.build/gen/go/listenup/listenup/protocolbuffers/go/listenup/auth/v1"
	permissionv1 "buf.build/gen/go/listenup/listenup/protocolbuffers/go/listenup/permission/v1"
	userv1 "buf.build/gen/go/listenup/listenup/protocolbuffers/go/listenup/user/v1"
	"connectrpc.com/connect"
	"context"
	"errors"
	"github.com/ListenUpApp/ListenUp/internal/logger"
	"github.com/ListenUpApp/ListenUp/internal/store"
	"github.com/ListenUpApp/ListenUp/internal/utils"
	"github.com/ListenUpApp/ListenUp/internal/validator"
	gonanoid "github.com/matoous/go-nanoid/v2"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
	"google.golang.org/protobuf/types/known/timestamppb"
	"time"
)

const (
	ACCESS_TTL  = 1 * time.Hour
	REFRESH_TTL = (14 * 24) * time.Hour
)

type AuthHandlers struct {
	userStore   store.UserStore
	authStore   store.AuthStore
	serverStore store.ServerStore
	authv1connect.UnimplementedAuthServiceHandler
}

func NewAuthHandlers(userStore store.UserStore, authStore store.AuthStore, serverStore store.ServerStore) *AuthHandlers {
	return &AuthHandlers{
		userStore:   userStore,
		authStore:   authStore,
		serverStore: serverStore,
	}
}

func (h *AuthHandlers) LoginUser(ctx context.Context, req *connect.Request[authv1.LoginRequest]) (*connect.Response[authv1.LoginResponse], error) {
	violations := validator.ValidateLoginRequest(req)
	if violations != nil {
		return nil, status.Errorf(codes.InvalidArgument, "Invalid login request: %v", violations)
	}

	user, err := h.userStore.GetUserByEmail(ctx, req.Msg.GetEmail())
	if err != nil {
		logger.Error("Could Not find User", "email", req.Msg.GetEmail())
		return nil, status.Errorf(codes.NotFound, "user not found")
	}

	if utils.CheckPasswordHash(req.Msg.GetPassword(), user.HashedPassword) == false {
		logger.Error("Could Not find User", "email", req.Msg.GetEmail())
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

	res := connect.NewResponse(&authv1.LoginResponse{
		AccessToken:  accessToken,
		RefreshToken: refreshToken,
		User:         user.User,
	})

	return res, nil
}

func (h *AuthHandlers) Register(ctx context.Context, req *connect.Request[authv1.RegisterRequest]) (*connect.Response[authv1.RegisterResponse], error) {
	//violations := validator.ValidateRegisterRequest(req)
	//if len(violations) > 0 {
	//	return nil, connect.NewError(connect.CodeInvalidArgument, common.InvalidArgumentError(violations))
	//}

	// Check if the user already exists.
	_, err := h.userStore.GetUserByEmail(ctx, req.Msg.GetEmail())
	if err == nil {
		return nil, connect.NewError(connect.CodeAlreadyExists, errors.New("A User with that username already exists"))
	}

	server, err := h.serverStore.GetServer(ctx)

	if err != nil {
		logger.Error("Unable to retrieve an instance of the server.")
		return nil, connect.NewError(connect.CodeInternal, errors.New("Could not retrieve server"))
	}
	if server == nil || server.Server == nil {
		logger.Error("Server or Server.Server is nil.")
		return nil, connect.NewError(connect.CodeInternal, errors.New("Invalid server state"))
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
		return nil, connect.NewError(connect.CodeInternal, errors.New("Could not generate ID"))
	}

	hashedPassword, err := utils.HashPassword(req.Msg.GetPassword())
	if err != nil {
		return nil, connect.NewError(connect.CodeInternal, errors.New("Failed to hash password"))
	}
	newUser := userv1.User{
		Id:                  id,
		Name:                req.Msg.GetName(),
		Email:               req.Msg.GetEmail(),
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
		logger.Error("Could not Save user to the database", "Email", req.Msg.GetEmail())
		return nil, connect.NewError(connect.CodeInternal, errors.New("Unable to save user to Database"))
	}

	if !server.Server.IsSetUp {
		err := h.serverStore.UpdateServer(ctx, func(server *authv1.AuthServer) error {
			server.Server.IsSetUp = true
			return nil
		})
		if err != nil {
			logger.Error("Could not update server setup status.")
			return nil, connect.NewError(connect.CodeInternal, errors.New("Could not update server setup status"))
		}
	}

	res := connect.NewResponse(&authv1.RegisterResponse{})
	return res, nil
}

func (h *AuthHandlers) RefreshToken(ctx context.Context, req *connect.Request[authv1.RefreshTokenRequest]) (*connect.Response[authv1.RefreshTokenResponse], error) {
	claims, err := utils.ParseToken(req.Msg.GetRefreshToken())
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

	if storedToken != req.Msg.GetRefreshToken() {
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
	res := connect.NewResponse(&authv1.RefreshTokenResponse{
		AccessToken: newAccessToken,
	})

	return res, nil
}
