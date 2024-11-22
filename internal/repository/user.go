package repository

import (
	"context"
	"github.com/ListenUpApp/ListenUp/internal/ent"
	"github.com/ListenUpApp/ListenUp/internal/ent/user"
	errorhandling "github.com/ListenUpApp/ListenUp/internal/error_handling"
	"github.com/ListenUpApp/ListenUp/internal/models"
	"log/slog"
)

type UserRepository struct {
	client *ent.Client
	logger *slog.Logger
}

func NewUserRepository(cfg Config) *UserRepository {
	return &UserRepository{
		client: cfg.Client,
		logger: cfg.Logger,
	}
}

func (u *UserRepository) GetUserById(ctx context.Context, id string) (*ent.User, error) {
	dbUser, err := u.client.User.Query().
		Where(user.IDEQ(id)).
		Only(ctx)

	if err != nil {
		if ent.IsNotFound(err) {
			return nil, errorhandling.NewNotFoundError("user not found")
		}
		u.logger.ErrorContext(ctx, "Failed to get user by ID",
			"user_id", id,
			"error", err)
		return nil, errorhandling.NewInternalError(err, "failed to query user")
	}

	return dbUser, nil
}

func (u *UserRepository) GetUserByEmail(ctx context.Context, email string) (*ent.User, error) {
	dbUser, err := u.client.User.Query().
		Where(user.EmailEQ(email)).
		Only(ctx)

	if err != nil {
		if ent.IsNotFound(err) {
			return nil, errorhandling.NewNotFoundError("user not found")
		}
		u.logger.ErrorContext(ctx, "Failed to get user by email",
			"email", email,
			"error", err)
		return nil, errorhandling.NewInternalError(err, "failed to query user")
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
		if ent.IsConstraintError(err) {
			u.logger.WarnContext(ctx, "Attempted to create user with existing email",
				"email", user.Email)
			return nil, errorhandling.NewConflictError("user with this email already exists")
		}
		u.logger.ErrorContext(ctx, "Failed to create user",
			"email", user.Email,
			"error", err)
		return nil, errorhandling.NewInternalError(err, "failed to create user")
	}

	u.logger.InfoContext(ctx, "User created successfully",
		"user_id", dbUser.ID,
		"email", dbUser.Email)

	return dbUser, nil
}
