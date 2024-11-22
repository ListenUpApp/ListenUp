package service

import (
	"context"
	"errors"
	"fmt"
	errorhandling "github.com/ListenUpApp/ListenUp/internal/error_handling"
	"github.com/ListenUpApp/ListenUp/internal/models"
	"github.com/ListenUpApp/ListenUp/internal/repository"
	"github.com/ListenUpApp/ListenUp/internal/util"
	"github.com/ListenUpApp/ListenUp/pkg/validator"
	"log/slog"
	"time"
)

type AuthService struct {
	userRepo   *repository.UserRepository
	serverRepo *repository.ServerRepository
	logger     *slog.Logger
}

func NewAuthService(cfg ServiceConfig) (*AuthService, error) {
	if cfg.UserRepo == nil {
		return nil, fmt.Errorf("user repository is required")
	}
	if cfg.ServerRepo == nil {
		return nil, fmt.Errorf("server repository is required")
	}
	if cfg.Logger == nil {
		return nil, fmt.Errorf("logger is required")
	}

	return &AuthService{
		userRepo:   cfg.UserRepo,
		serverRepo: cfg.ServerRepo,
		logger:     cfg.Logger,
	}, nil
}

func (s *AuthService) RegisterUser(ctx context.Context, req models.RegisterRequest) error {
	// Validate request
	if err := s.ValidateRegisterRequest(req); err != nil {
		return err
	}

	// Check for existing user
	existingUser, err := s.userRepo.GetUserByEmail(ctx, req.Email)
	if err != nil {
		var appErr *errorhandling.AppError
		if errors.As(err, &appErr) && appErr.Type == errorhandling.ErrorTypeNotFound {
			// This is fine - user doesn't exist
		} else {
			s.logger.ErrorContext(ctx, "Failed to check existing user",
				"email", req.Email,
				"error", err)
			return errorhandling.NewInternalError(err, "error checking existing user")
		}
	}

	if existingUser != nil {
		s.logger.WarnContext(ctx, "Attempted to register existing email",
			"email", req.Email)
		return errorhandling.NewConflictError("user with this email already exists")
	}

	// Hash password
	hashedPassword, err := util.HashPassword(req.Password)
	if err != nil {
		s.logger.ErrorContext(ctx, "Failed to hash password", "error", err)
		return errorhandling.NewInternalError(err, "processing registration")
	}

	// Create user
	createUserParams := models.CreateUser{
		FirstName:      req.FirstName,
		LastName:       req.LastName,
		Email:          req.Email,
		HashedPassword: hashedPassword,
	}

	_, err = s.userRepo.CreateUser(ctx, createUserParams)
	if err != nil {
		s.logger.ErrorContext(ctx, "Failed to create user",
			"email", req.Email,
			"error", err)
		return errorhandling.NewInternalError(err, "error creating user")
	}
	s.logger.InfoContext(ctx, "User registered successfully",
		"email", req.Email)

	_, err = s.serverRepo.UpdateServerSetup(ctx, true)

	if err != nil {
		s.logger.ErrorContext(ctx, "Failed to update server's setup status", "error", err)
		return errorhandling.NewInternalError(err, "error updating server")
	}

	return nil
}

func (s *AuthService) LoginUser(ctx context.Context, req models.LoginRequest) (*models.LoginResponse, error) {
	// Validate request
	if err := s.ValidateLoginRequest(req); err != nil {
		return nil, err
	}

	dbUser, err := s.userRepo.GetUserByEmail(ctx, req.Email)
	if err != nil {
		var appErr *errorhandling.AppError
		if errors.As(err, &appErr) && appErr.Type == errorhandling.ErrorTypeNotFound {
			return nil, errorhandling.NewNotFoundError("dbUser with that email does not exist")
		}
	}

	if !util.CheckPasswordHash(req.Password, dbUser.PasswordHash) {
		return nil, errorhandling.NewUnauthorizedError("invalid credentials")
	}

	token, err := util.GenerateToken(dbUser.ID, 0, dbUser.Email, 24*30*time.Hour)
	if err != nil {
		return nil, errorhandling.NewInternalError(err, "failed to generate token")
	}
	user := models.User{
		ID:        dbUser.ID,
		Email:     dbUser.Email,
		FirstName: dbUser.FirstName,
		LastName:  dbUser.LastName,
	}

	return &models.LoginResponse{
		Token: token,
		User:  user,
	}, nil
}

func (s *AuthService) IsServerSetup(ctx context.Context) (bool, error) {
	if s.serverRepo == nil {
		return false, fmt.Errorf("server repository not initialized")
	}

	srv, err := s.serverRepo.GetServer(ctx)
	if err != nil {
		s.logger.Error("failed to get server status", "error", err)
		return false, fmt.Errorf("failed to get server: %w", err)
	}

	return srv.Setup, nil
}

func (s *AuthService) ValidateRegisterRequest(req models.RegisterRequest) error {
	if errors := validator.Validate(req); len(errors) > 0 {
		return errorhandling.NewValidationError("validation failed").WithData(errors)
	}
	return nil
}

func (s *AuthService) ValidateLoginRequest(req models.LoginRequest) error {
	if errors := validator.Validate(req); len(errors) > 0 {
		return errorhandling.NewValidationError("validation failed").WithData(errors)
	}
	return nil
}
