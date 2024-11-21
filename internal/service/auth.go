package service

import (
	"context"
	"fmt"
	"github.com/ListenUpApp/ListenUp/internal/models"
	"github.com/ListenUpApp/ListenUp/internal/repository"
	"github.com/ListenUpApp/ListenUp/internal/util"
)

type AuthService struct {
	userRepo *repository.UserRepository
}

func NewAuthService(userRepo *repository.UserRepository) *AuthService {
	return &AuthService{
		userRepo: userRepo,
	}
}

func (s *AuthService) RegisterUser(ctx context.Context, req models.RegisterRequest) error {
	exists, err := s.userRepo.GetUserByEmail(ctx, req.Email)
	if exists != nil {
		fmt.Println("User Already Exists")
		return fmt.Errorf("user already exists")
	}

	hashedPassword, err := util.HashPassword(req.Password)
	if err != nil {
		fmt.Println("error hashing password")
		return fmt.Errorf("error hashing password")
	}
	createUserParams := models.CreateUser{
		FirstName:      req.FirstName,
		LastName:       req.LastName,
		Email:          req.Email,
		HashedPassword: hashedPassword,
	}
	_, err = s.userRepo.CreateUser(ctx, createUserParams)
	if err != nil {
		fmt.Println("error creating user")
		return fmt.Errorf("error creating user")

	}
	return nil
}
