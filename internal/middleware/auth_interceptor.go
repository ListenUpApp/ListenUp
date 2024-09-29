package middleware

import (
	"context"
	"github.com/ListenUpApp/ListenUp/internal/utils"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/metadata"
	"google.golang.org/grpc/status"
	"strings"
	"time"
)

// Define a key for our claims in the context
type contextKey string

const claimsKey contextKey = "claims"

// Claims is our custom claims struct
type Claims struct {
	UserID string `json:"user_id"`
	Email  string `json:"email"`
	Role   string `json:"role"`
	Exp    int64  `json:"exp"`
	// Add other fields as needed
}

var unauthenticatedMethods = map[string]bool{
	"/listenup.auth.v1.AuthService/Login":         true,
	"/listenup.auth.v1.AuthService/Register":      true,
	"/listenup.server.v1.ServerService/Ping":      true,
	"/listenup.server.v1.ServerService/GetServer": true,
}

func AuthInterceptor(ctx context.Context, req interface{}, info *grpc.UnaryServerInfo, handler grpc.UnaryHandler) (interface{}, error) {
	println(info.FullMethod)
	if unauthenticatedMethods[info.FullMethod] {
		return handler(ctx, req)
	}

	md, ok := metadata.FromIncomingContext(ctx)
	if !ok {
		return nil, status.Errorf(codes.Unauthenticated, "missing metadata")
	}

	authHeader, ok := md["authorization"]
	if !ok || len(authHeader) == 0 {
		return nil, status.Errorf(codes.Unauthenticated, "missing authorization token")
	}

	token := strings.TrimPrefix(authHeader[0], "Bearer ")

	parsedClaims, err := utils.ParseToken(token)
	if err != nil {
		return nil, status.Errorf(codes.Unauthenticated, "invalid token: %v", err)
	}

	claims := &Claims{}
	if userID, ok := parsedClaims["user_id"].(string); ok {
		claims.UserID = userID
	}
	if email, ok := parsedClaims["email"].(string); ok {
		claims.Email = email
	}
	if role, ok := parsedClaims["role"].(string); ok {
		claims.Role = role
	}
	if exp, ok := parsedClaims["exp"].(float64); ok {
		claims.Exp = int64(exp)
	}

	if time.Now().Unix() > claims.Exp {
		return nil, status.Errorf(codes.Unauthenticated, "token has expired")
	}

	//TODO add role and other authorization logic here.

	newCtx := context.WithValue(ctx, claimsKey, claims)

	return handler(newCtx, req)
}

func GetClaimsFromContext(ctx context.Context) (*Claims, error) {
	claims, ok := ctx.Value(claimsKey).(*Claims)
	if !ok {
		return nil, status.Errorf(codes.Internal, "claims not found in context")
	}
	return claims, nil
}
