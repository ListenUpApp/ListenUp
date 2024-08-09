package auth

import (
	"buf.build/gen/go/listenup/listenup/connectrpc/go/listenup/auth/v1/authv1connect"
	authv1 "buf.build/gen/go/listenup/listenup/protocolbuffers/go/listenup/auth/v1"
	userv1 "buf.build/gen/go/listenup/listenup/protocolbuffers/go/listenup/user/v1"
	"connectrpc.com/connect"
	"context"
	"errors"
	"github.com/ListenUpApp/ListenUp/db"
	"github.com/ListenUpApp/ListenUp/internal/common"
	"github.com/golang-jwt/jwt"
	gonanoid "github.com/matoous/go-nanoid/v2"
	"golang.org/x/crypto/bcrypt"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
	"google.golang.org/protobuf/types/known/timestamppb"
	"time"
)

type AuthHandlers struct {
	userStore  db.UserStore
	authStore  db.AuthStore
	jwtSecret  []byte
	accessTTL  time.Duration
	refreshTTL time.Duration
	authv1connect.UnimplementedAuthServiceHandler
}

func NewAuthHandlers(userStore db.UserStore, authStore db.AuthStore, jwtSecret []byte, accessTTL, refreshTTL time.Duration) *AuthHandlers {
	return &AuthHandlers{
		userStore:  userStore,
		authStore:  authStore,
		jwtSecret:  jwtSecret,
		accessTTL:  accessTTL,
		refreshTTL: refreshTTL,
	}
}

func (h *AuthHandlers) LoginUser(ctx context.Context, req *connect.Request[authv1.LoginRequest]) (*connect.Response[authv1.LoginResponse], error) {
	// Validate request
	violations := ValidateLoginRequest(req)
	if violations != nil {
		return nil, status.Errorf(codes.InvalidArgument, "Invalid login request: %v", violations)
	}

	// Get user from database
	user, err := h.userStore.GetUserByEmail(ctx, req.Msg.GetEmail())
	if err != nil {
		return nil, status.Errorf(codes.NotFound, "user not found")
	}

	// Check password
	if checkPasswordHash(req.Msg.GetPassword(), user.HashedPassword) == false {
		return nil, status.Errorf(codes.InvalidArgument, "incorrect password")
	}

	// Generate access token
	accessToken, err := h.generateToken(user.User.Id, user.User.Role, user.User.Email, h.accessTTL)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "could not generate access token")
	}

	// Generate refresh token
	refreshToken, err := h.generateToken(user.User.Id, "", "", h.refreshTTL)
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
	violations := ValidateRegisterRequest(req)
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
	hashedPassword, err := hashPassword(req.Msg.GetPassword())
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
	claims, err := h.parseToken(req.Msg.GetRefreshToken())
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
	newAccessToken, err := h.generateToken(user.User.Id, user.User.Role, user.User.Email, h.accessTTL)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "could not generate new access token")
	}

	// Generate new refresh token
	newRefreshToken, err := h.generateToken(user.User.Id, "", "", h.refreshTTL)
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

func (h *AuthHandlers) generateToken(userID, role, email string, expiration time.Duration) (string, error) {
	claims := jwt.MapClaims{
		"user_id": userID,
		"exp":     time.Now().Add(expiration).Unix(),
	}

	if role != "" {
		claims["role"] = role
	}
	if email != "" {
		claims["email"] = email
	}

	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	return token.SignedString(h.jwtSecret)
}

func (h *AuthHandlers) parseToken(tokenString string) (jwt.MapClaims, error) {
	token, err := jwt.Parse(tokenString, func(token *jwt.Token) (interface{}, error) {
		if _, ok := token.Method.(*jwt.SigningMethodHMAC); !ok {
			return nil, errors.New("unexpected signing method")
		}
		return h.jwtSecret, nil
	})

	if err != nil {
		return nil, err
	}

	if claims, ok := token.Claims.(jwt.MapClaims); ok && token.Valid {
		return claims, nil
	}

	return nil, errors.New("invalid token")
}

func hashPassword(password string) (string, error) {
	hashedPassword, err := bcrypt.GenerateFromPassword([]byte(password), bcrypt.DefaultCost)
	if err != nil {
		return "", err
	}
	return string(hashedPassword), nil
}

func checkPasswordHash(password, hash string) bool {
	err := bcrypt.CompareHashAndPassword([]byte(password), []byte(password))
	if err != nil {
		return false
	}
	return true
}
