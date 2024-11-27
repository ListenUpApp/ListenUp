package service

import (
	"context"
	"fmt"
	"time"

	appErr "github.com/ListenUpApp/ListenUp/internal/error"
	logging "github.com/ListenUpApp/ListenUp/internal/logger"
	"github.com/ListenUpApp/ListenUp/internal/models"
	"github.com/ListenUpApp/ListenUp/internal/repository"
	"github.com/ListenUpApp/ListenUp/internal/util"
	"github.com/go-playground/validator/v10"
)

type AuthService struct {
	userRepo   *repository.UserRepository
	serverRepo *repository.ServerRepository
	logger     *logging.AppLogger
	validator  *validator.Validate
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
		validator:  cfg.Validator,
	}, nil
}

func (s *AuthService) RegisterUser(ctx context.Context, req models.RegisterRequest) error {
	// Validate request
	if err := s.ValidateRegisterRequest(req); err != nil {
		return err // ValidateRegisterRequest already returns proper error type
	}

	// Check for existing user
	existingUser, err := s.userRepo.GetUserByEmail(ctx, req.Email)
	if err != nil {
		err = appErr.HandleRepositoryError(err, "RegisterUser", map[string]interface{}{
			"email": req.Email,
		})
		if appErr.IsNotFound(err) {
			// This is fine - user doesn't exist
		} else {
			s.logger.ErrorContext(ctx, "Failed to check existing user",
				"email", req.Email,
				"error", err)
			return err
		}
	}

	if existingUser != nil {
		s.logger.WarnContext(ctx, "Attempted to register existing email",
			"email", req.Email)
		return appErr.NewServiceError(appErr.ErrConflict, "user with this email already exists", nil).
			WithOperation("RegisterUser").
			WithField("email").
			WithData(map[string]interface{}{"email": req.Email})
	}

	// Hash password
	hashedPassword, err := util.HashPassword(req.Password)
	if err != nil {
		s.logger.ErrorContext(ctx, "Failed to hash password", "error", err)
		return appErr.NewServiceError(appErr.ErrInternal, "failed to process registration", err).
			WithOperation("RegisterUser")
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
		return appErr.HandleRepositoryError(err, "RegisterUser", map[string]interface{}{
			"email": req.Email,
		})
	}

	// Update server setup status
	_, err = s.serverRepo.UpdateServerSetup(ctx, true)
	if err != nil {
		return appErr.HandleRepositoryError(err, "RegisterUser", map[string]interface{}{
			"operation": "update_server_setup",
		})
	}

	s.logger.InfoContext(ctx, "User registered successfully",
		"email", req.Email)

	return nil
}

func (s *AuthService) LoginUser(ctx context.Context, req models.LoginRequest) (*models.LoginResponse, error) {
	// Validate request
	if err := s.ValidateLoginRequest(req); err != nil {
		return nil, err // ValidateLoginRequest already returns proper error type
	}

	// Get user by email
	dbUser, err := s.userRepo.GetUserByEmail(ctx, req.Email)
	if err != nil {
		err = appErr.HandleRepositoryError(err, "LoginUser", map[string]interface{}{
			"email": req.Email,
		})
		if appErr.IsNotFound(err) {
			return nil, appErr.NewServiceError(appErr.ErrUnauthorized, "invalid email or password", nil).
				WithOperation("LoginUser")
		}
		return nil, err
	}

	// Check password
	if !util.CheckPasswordHash(req.Password, dbUser.PasswordHash) {
		return nil, appErr.NewServiceError(appErr.ErrUnauthorized, "invalid email or password", nil).
			WithOperation("LoginUser")
	}

	// Generate token
	token, err := util.GenerateToken(dbUser.ID, 0, dbUser.Email, 24*30*time.Hour)
	if err != nil {
		return nil, appErr.NewServiceError(appErr.ErrInternal, "failed to generate token", err).
			WithOperation("LoginUser").
			WithData(map[string]interface{}{"user_id": dbUser.ID})
	}

	return &models.LoginResponse{
		Token: token,
		User: models.User{
			ID:        dbUser.ID,
			Email:     dbUser.Email,
			FirstName: dbUser.FirstName,
			LastName:  dbUser.LastName,
		},
	}, nil
}

func (s *AuthService) IsServerSetup(ctx context.Context) (bool, error) {
	if s.serverRepo == nil {
		return false, appErr.NewServiceError(appErr.ErrInternal, "server repository not initialized", nil).
			WithOperation("IsServerSetup")
	}

	srv, err := s.serverRepo.GetServer(ctx)
	if err != nil {
		return false, appErr.HandleRepositoryError(err, "IsServerSetup", nil)
	}

	return srv.Setup, nil
}

func (s *AuthService) ValidateRegisterRequest(req models.RegisterRequest) error {
	err := s.validator.Struct(req)
	if err == nil {
		return nil
	}

	errors := make(map[string]string)
	for _, err := range err.(validator.ValidationErrors) {
		switch err.Tag() {
		case "required":
			errors[err.Field()] = "This field is required"
		case "email":
			errors[err.Field()] = "Invalid email format"
		case "min":
			errors[err.Field()] = "Must be at least " + err.Param() + " characters long"
		case "max":
			errors[err.Field()] = "Must not exceed " + err.Param() + " characters"
		case "passwd":
			errors[err.Field()] = "Password must be at least 8 characters and include uppercase, lowercase, number, and special character"
		case "eqfield":
			errors[err.Field()] = "Passwords do not match"
		default:
			errors[err.Field()] = "Invalid value"
		}
	}

	return appErr.NewServiceError(appErr.ErrValidation, "validation failed", err).
		WithOperation("ValidateRegisterRequest").
		WithData(errors)
}

func (s *AuthService) ValidateLoginRequest(req models.LoginRequest) error {
	err := s.validator.Struct(req)
	if err == nil {
		return nil
	}

	errors := make(map[string]string)
	for _, err := range err.(validator.ValidationErrors) {
		switch err.Tag() {
		case "required":
			errors[err.Field()] = "This field is required"
		case "email":
			errors[err.Field()] = "Invalid email format"
		default:
			errors[err.Field()] = "Invalid value"
		}
	}

	return appErr.NewServiceError(appErr.ErrValidation, "validation failed", err).
		WithOperation("ValidateLoginRequest").
		WithData(errors)
}
