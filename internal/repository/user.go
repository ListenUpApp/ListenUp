package repository

import (
	"context"
	"fmt"
	"log/slog"

	"github.com/ListenUpApp/ListenUp/internal/ent"
	"github.com/ListenUpApp/ListenUp/internal/ent/library"
	"github.com/ListenUpApp/ListenUp/internal/ent/user"
	errorhandling "github.com/ListenUpApp/ListenUp/internal/error_handling"
	"github.com/ListenUpApp/ListenUp/internal/models"
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

func (u *UserRepository) AddLibraryToUser(ctx context.Context, userId string, libraryId string) error {
	// Start transaction
	tx, err := u.client.Tx(ctx)
	if err != nil {
		return fmt.Errorf("starting transaction: %w", err)
	}
	defer tx.Rollback()

	// Get user
	user, err := tx.User.Get(ctx, userId)
	if err != nil {
		if ent.IsNotFound(err) {
			return errorhandling.NewNotFoundError("user not found")
		}
		return errorhandling.NewInternalError(err, "failed to get user")
	}

	// Update user by adding library
	err = tx.User.UpdateOne(user).
		AddLibraryIDs(libraryId).
		Exec(ctx)
	if err != nil {
		return errorhandling.NewInternalError(err, "failed to add library to user")
	}

	return tx.Commit()
}

func (u *UserRepository) UpdateActiveLibrary(ctx context.Context, userId string, libraryId string) error {
	// Use a transaction to ensure atomicity
	tx, err := u.client.Tx(ctx)
	if err != nil {
		return fmt.Errorf("starting transaction: %w", err)
	}
	defer tx.Rollback()

	// Verify the user has access to the library
	exists, err := tx.User.Query().
		Where(
			user.ID(userId),
			user.HasLibrariesWith(library.ID(libraryId)),
		).
		Exist(ctx)
	if err != nil {
		return fmt.Errorf("verifying library access: %w", err)
	}
	if !exists {
		return fmt.Errorf("user does not have access to library")
	}

	// Update the active library
	err = tx.User.UpdateOne(
		tx.User.Query().
			Where(user.ID(userId)).
			OnlyX(ctx),
	).
		SetActiveLibraryID(libraryId).
		Exec(ctx)
	if err != nil {
		return fmt.Errorf("updating active library: %w", err)
	}

	// Commit the transaction
	return tx.Commit()
}
