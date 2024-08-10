package auth

import (
	"buf.build/gen/go/listenup/listenup/connectrpc/go/listenup/auth/v1/authv1connect"
	authv1 "buf.build/gen/go/listenup/listenup/protocolbuffers/go/listenup/auth/v1"
	userv1 "buf.build/gen/go/listenup/listenup/protocolbuffers/go/listenup/user/v1"
	"connectrpc.com/connect"
	"context"
	"github.com/ListenUpApp/ListenUp/internal/common"
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
	userStore store.UserStore
	authStore store.AuthStore
	authv1connect.UnimplementedAuthServiceHandler
}

func NewAuthHandlers(userStore store.UserStore, authStore store.AuthStore) *AuthHandlers {
	return &AuthHandlers{
		userStore: userStore,
		authStore: authStore,
	}
}

func (h *AuthHandlers) LoginUser(ctx context.Context, req *connect.Request[authv1.LoginRequest]) (*connect.Response[authv1.LoginResponse], error) {
	// Validate request
	violations := validator.ValidateLoginRequest(req)
	if violations != nil {
		return nil, status.Errorf(codes.InvalidArgument, "Invalid login request: %v", violations)
	}

	// Get user from database
	user, err := h.userStore.GetUserByEmail(ctx, req.Msg.GetEmail())
	if err != nil {
		return nil, status.Errorf(codes.NotFound, "user not found")
	}

	// Check password
	if utils.CheckPasswordHash(req.Msg.GetPassword(), user.HashedPassword) == false {
		return nil, status.Errorf(codes.InvalidArgument, "incorrect password")
	}

	// Generate access token
	//todo Change these time variables to Server Config variables
	accessToken, err := utils.GenerateToken(user.User.Id, int32(user.User.Role), user.User.Email, time.Hour)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "could not generate access token")
	}

	// Generate refresh token
	//todo Change these time variables to Server Config variables
	refreshToken, err := utils.GenerateToken(user.User.Id, int32(user.User.Role), user.User.Email, time.Hour)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "could not generate refresh token")
	}

	// Store refresh token in the database
	err = h.authStore.StoreRefreshToken(ctx, user.User.Id, refreshToken)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "could not store refresh token")
	}

	// Create response
	res := connect.NewResponse(&authv1.LoginResponse{
		AccessToken:  accessToken,
		RefreshToken: refreshToken,
		User:         user.User,
	})

	return res, nil
}

func (h *AuthHandlers) RegisterUser(ctx context.Context, req *connect.Request[authv1.RegisterRequest]) (*connect.Response[authv1.RegisterResponse], error) {
	violations := validator.ValidateRegisterRequest(req)
	if len(violations) > 0 {
		return nil, connect.NewError(connect.CodeInvalidArgument, common.InvalidArgumentError(violations))
	}

	// Check if the user already exists.
	_, err := h.userStore.GetUserByEmail(ctx, req.Msg.GetEmail())
	if err == nil {
		return nil, status.Errorf(codes.AlreadyExists, "A User with that username already exists")
	}

	// TODO check if the server is in Init Mode or not to define the root user.

	id, err := gonanoid.Generate("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz", 8)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "Could not generate ID")
	}

	// Hash the password.
	hashedPassword, err := utils.HashPassword(req.Msg.GetPassword())
	if err != nil {
		return nil, status.Errorf(codes.Internal, "Failed to hash password")
	}
	newUser := userv1.User{
		Id:                  id,
		Name:                req.Msg.GetName(),
		Email:               req.Msg.GetEmail(),
		CreatedAt:           timestamppb.Now(),
		LastLogin:           timestamppb.Now(),
		Role:                0,
		OverridePermissions: nil,
	}

	createUserParms := authv1.AuthUser{
		HashedPassword: hashedPassword,
		User:           &newUser,
	}
	err = h.userStore.CreateUser(ctx, &createUserParms)
	// TODO check if this was the root user let's change the Init flag.
	if err != nil {
		// Do something
	}

	res := connect.NewResponse(&authv1.RegisterResponse{})
	return res, nil
}

func (h *AuthHandlers) RefreshToken(ctx context.Context, req *connect.Request[authv1.RefreshTokenRequest]) (*connect.Response[authv1.RefreshTokenResponse], error) {
	// Parse and validate refresh token
	claims, err := utils.ParseToken(req.Msg.GetRefreshToken())
	if err != nil {
		return nil, status.Errorf(codes.Unauthenticated, "invalid refresh token")
	}

	userID, ok := claims["user_id"].(string)
	if !ok {
		return nil, status.Errorf(codes.Internal, "invalid claim format")
	}

	// Check if the refresh token exists in the database
	storedToken, err := h.authStore.GetRefreshToken(ctx, userID)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "could not retrieve refresh token")
	}

	if storedToken != req.Msg.GetRefreshToken() {
		return nil, status.Errorf(codes.Unauthenticated, "refresh token has been revoked")
	}

	// Get user from database
	user, err := h.userStore.GetUserById(ctx, userID)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "could not retrieve user")
	}

	// Generate new access token
	//todo Change these time variables to Server Config variables
	newAccessToken, err := utils.GenerateToken(user.User.Id, int32(user.User.Role), user.User.Email, ACCESS_TTL)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "could not generate new access token")
	}

	// Generate new refresh token
	//todo Change these time variables to Server Config variables
	newRefreshToken, err := utils.GenerateToken(user.User.Id, int32(user.User.Role), user.User.Email, REFRESH_TTL)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "could not generate new refresh token")
	}

	// Update refresh token in the database
	err = h.authStore.UpdateRefreshToken(ctx, user.User.Id, newRefreshToken)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "could not update refresh token")
	}

	// Create response
	res := connect.NewResponse(&authv1.RefreshTokenResponse{
		AccessToken: newAccessToken,
	})

	return res, nil
}
