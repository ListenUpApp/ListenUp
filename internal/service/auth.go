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
)

type AuthService struct {
	userRepo *repository.UserRepository
	logger   *slog.Logger
}

func NewAuthService(cfg ServiceConfig) (*AuthService, error) {
	if cfg.UserRepo == nil {
		return nil, fmt.Errorf("user repository is required")
	}
	if cfg.Logger == nil {
		return nil, fmt.Errorf("logger is required")
	}

	return &AuthService{
		userRepo: cfg.UserRepo,
		logger:   cfg.Logger,
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

	return nil
}

func (s *AuthService) ValidateRegisterRequest(req models.RegisterRequest) error {
	if errors := validator.Validate(req); len(errors) > 0 {
		return errorhandling.NewValidationError("validation failed").WithData(errors)
	}
	return nil
}
