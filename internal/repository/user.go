package repository

import (
	"context"
	"fmt"
	"github.com/ListenUpApp/ListenUp/internal/ent"
	"github.com/ListenUpApp/ListenUp/internal/ent/user"
	"github.com/ListenUpApp/ListenUp/internal/models"
)

type UserRepository struct {
	client *ent.Client
}

func NewUserRepository(client *ent.Client) *UserRepository {
	return &UserRepository{
		client: client,
	}
}

func (u *UserRepository) GetUserById(ctx context.Context, id string) (*ent.User, error) {
	dbUser, err := u.client.User.Query().Where(user.IDEQ(id)).First(ctx)
	if err != nil {
		if ent.IsNotFound(err) {
			return nil, fmt.Errorf("user not found")
		}
		return nil, fmt.Errorf("error querying user")
	}
	return dbUser, nil
}

func (u *UserRepository) GetUserByEmail(ctx context.Context, email string) (*ent.User, error) {
	dbUser, err := u.client.User.Query().Where(user.EmailEQ(email)).First(ctx)
	if err != nil {
		if ent.IsNotFound(err) {
			return nil, fmt.Errorf("user not found")
		}
		return nil, fmt.Errorf("error querying user")
	}
	return dbUser, nil
}

func (u *UserRepository) CreateUser(ctx context.Context, user models.CreateUser) (*ent.User, error) {
	dbUser, err := u.client.User.
		Create().
		SetFirstName(user.FirstName).
		SetLastName(user.LastName).
		SetEmail(user.Email).
		SetPasswordHash(user.HashedPassword).
		Save(ctx)

	if err != nil {
		fmt.Println("Error creating user %w", err)
		return nil, fmt.Errorf("error creating user")
	}
	return dbUser, nil
}
