package repository

import (
	"context"

	"github.com/ListenUpApp/ListenUp/internal/ent"
	"github.com/ListenUpApp/ListenUp/internal/ent/library"
	"github.com/ListenUpApp/ListenUp/internal/ent/user"
	appErr "github.com/ListenUpApp/ListenUp/internal/error"
	logging "github.com/ListenUpApp/ListenUp/internal/logger"
	"github.com/ListenUpApp/ListenUp/internal/models"
)

type UserRepository struct {
	client *ent.Client
	logger *logging.AppLogger
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
			return nil, appErr.NewRepositoryError(appErr.ErrNotFound, "user not found", err).
				WithOperation("GetUserById").
				WithData(map[string]interface{}{"user_id": id})
		}
		u.logger.ErrorContext(ctx, "Failed to get user by ID",
			"user_id", id,
			"error", err)
		return nil, appErr.NewRepositoryError(appErr.ErrDatabase, "failed to query user", err).
			WithOperation("GetUserById").
			WithData(map[string]interface{}{"user_id": id})
	}

	return dbUser, nil
}

func (u *UserRepository) GetUserByEmail(ctx context.Context, email string) (*ent.User, error) {
	dbUser, err := u.client.User.Query().
		Where(user.EmailEQ(email)).
		Only(ctx)

	if err != nil {
		if ent.IsNotFound(err) {
			return nil, appErr.NewRepositoryError(appErr.ErrNotFound, "user not found", err).
				WithOperation("GetUserByEmail").
				WithField("email").
				WithData(map[string]interface{}{"email": email})
		}
		u.logger.ErrorContext(ctx, "Failed to get user by email",
			"email", email,
			"error", err)
		return nil, appErr.NewRepositoryError(appErr.ErrDatabase, "failed to query user", err).
			WithOperation("GetUserByEmail").
			WithData(map[string]interface{}{"email": email})
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
			return nil, appErr.NewRepositoryError(appErr.ErrConflict, "user with this email already exists", err).
				WithOperation("CreateUser").
				WithField("email").
				WithData(map[string]interface{}{"email": user.Email})
		}
		u.logger.ErrorContext(ctx, "Failed to create user",
			"email", user.Email,
			"error", err)
		return nil, appErr.NewRepositoryError(appErr.ErrDatabase, "failed to create user", err).
			WithOperation("CreateUser").
			WithData(map[string]interface{}{
				"email":     user.Email,
				"firstName": user.FirstName,
				"lastName":  user.LastName,
			})
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
		return appErr.NewRepositoryError(appErr.ErrDatabase, "failed to start transaction", err).
			WithOperation("AddLibraryToUser")
	}
	defer tx.Rollback()

	// Get user
	user, err := tx.User.Get(ctx, userId)
	if err != nil {
		if ent.IsNotFound(err) {
			return appErr.NewRepositoryError(appErr.ErrNotFound, "user not found", err).
				WithOperation("AddLibraryToUser").
				WithData(map[string]interface{}{"user_id": userId})
		}
		return appErr.NewRepositoryError(appErr.ErrDatabase, "failed to get user", err).
			WithOperation("AddLibraryToUser").
			WithData(map[string]interface{}{"user_id": userId})
	}

	// Update user by adding library
	err = tx.User.UpdateOne(user).
		AddLibraryIDs(libraryId).
		Exec(ctx)
	if err != nil {
		return appErr.NewRepositoryError(appErr.ErrDatabase, "failed to add library to user", err).
			WithOperation("AddLibraryToUser").
			WithData(map[string]interface{}{
				"user_id":    userId,
				"library_id": libraryId,
			})
	}

	if err := tx.Commit(); err != nil {
		return appErr.NewRepositoryError(appErr.ErrDatabase, "failed to commit transaction", err).
			WithOperation("AddLibraryToUser").
			WithData(map[string]interface{}{
				"user_id":    userId,
				"library_id": libraryId,
			})
	}

	return nil
}

func (u *UserRepository) UpdateActiveLibrary(ctx context.Context, userId string, libraryId string) error {
	// Start transaction
	tx, err := u.client.Tx(ctx)
	if err != nil {
		return appErr.NewRepositoryError(appErr.ErrDatabase, "failed to start transaction", err).
			WithOperation("UpdateActiveLibrary")
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
		return appErr.NewRepositoryError(appErr.ErrDatabase, "failed to verify library access", err).
			WithOperation("UpdateActiveLibrary").
			WithData(map[string]interface{}{
				"user_id":    userId,
				"library_id": libraryId,
			})
	}
	if !exists {
		return appErr.NewRepositoryError(appErr.ErrUnauthorized, "user does not have access to library", nil).
			WithOperation("UpdateActiveLibrary").
			WithData(map[string]interface{}{
				"user_id":    userId,
				"library_id": libraryId,
			})
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
		return appErr.NewRepositoryError(appErr.ErrDatabase, "failed to update active library", err).
			WithOperation("UpdateActiveLibrary").
			WithData(map[string]interface{}{
				"user_id":    userId,
				"library_id": libraryId,
			})
	}

	if err := tx.Commit(); err != nil {
		return appErr.NewRepositoryError(appErr.ErrDatabase, "failed to commit transaction", err).
			WithOperation("UpdateActiveLibrary").
			WithData(map[string]interface{}{
				"user_id":    userId,
				"library_id": libraryId,
			})
	}

	return nil
}
