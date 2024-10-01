package store

import (
	authv1 "buf.build/gen/go/listenup/listenup/protocolbuffers/go/listenup/auth/v1"
	"context"
	"google.golang.org/protobuf/types/known/timestamppb"
	"os"
	"testing"

	userv1 "buf.build/gen/go/listenup/listenup/protocolbuffers/go/listenup/user/v1"
	"github.com/dgraph-io/badger/v4"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func createTestUser() *userv1.User {
	return &userv1.User{
		Id:               "id-1",
		Email:            "first-user@example.com",
		Name:             "First User",
		CurrentLibraryId: "123",
		Preferences:      &userv1.UserPreferences{},
		LibraryIds:       []string{"default-library"},
		CreatedAt:        timestamppb.Now(),
		LastLogin:        timestamppb.Now(),
	}
}

func createSecondTestUser() *userv1.User {
	return &userv1.User{
		Id:               "id-2",
		Email:            "second-user@example.com",
		Name:             "Second User",
		CurrentLibraryId: "123",
		Preferences:      &userv1.UserPreferences{},
		LibraryIds:       []string{"default-library"},
		CreatedAt:        timestamppb.Now(),
		LastLogin:        timestamppb.Now(),
	}
}

func setupTestDB(t *testing.T) (*badger.DB, func()) {
	dir, err := os.MkdirTemp("", "badger-test")
	require.NoError(t, err)

	db, err := badger.Open(badger.DefaultOptions(dir))
	require.NoError(t, err)

	return db, func() {
		db.Close()
		os.RemoveAll(dir)
	}
}

func TestCreateUser(t *testing.T) {
	db, cleanup := setupTestDB(t)
	defer cleanup()

	store := NewBadgerUserStore(db)

	user := createTestUser()

	err := store.CreateUser(context.Background(), &authv1.AuthUser{
		HashedPassword: "password123",
		User:           user,
	})

	assert.NoError(t, err)

	// Verify user was created
	createdUser, err := store.GetUserById(context.Background(), user.Id)
	assert.NoError(t, err)
	assert.Equal(t, user.Id, createdUser.User.Id)
	assert.Equal(t, user.Email, createdUser.User.Email)
	assert.Equal(t, user.Name, createdUser.User.Name)
}

func TestGetUserById(t *testing.T) {
	db, cleanup := setupTestDB(t)
	defer cleanup()

	store := NewBadgerUserStore(db)

	user := createTestUser()

	err := store.CreateUser(context.Background(), &authv1.AuthUser{
		HashedPassword: "password123",
		User:           user,
	})
	require.NoError(t, err)

	fetchedUser, err := store.GetUserById(context.Background(), user.Id)
	assert.NoError(t, err)
	assert.Equal(t, user.Id, fetchedUser.User.Id)
	assert.Equal(t, user.Email, fetchedUser.User.Email)
	assert.Equal(t, user.Name, fetchedUser.User.Name)

	// Test non-existent user
	_, err = store.GetUserById(context.Background(), "non-existent-id")
	assert.Error(t, err)
}

func TestGetUserByEmail(t *testing.T) {
	db, cleanup := setupTestDB(t)
	defer cleanup()

	store := NewBadgerUserStore(db)

	user := createTestUser()

	err := store.CreateUser(context.Background(), &authv1.AuthUser{
		HashedPassword: "password123",
		User:           user,
	})
	require.NoError(t, err)

	fetchedUser, err := store.GetUserByEmail(context.Background(), user.Email)
	assert.NoError(t, err)
	assert.Equal(t, user.Id, fetchedUser.User.Id)
	assert.Equal(t, user.Email, fetchedUser.User.Email)
	assert.Equal(t, user.Name, fetchedUser.User.Name)

	// Test case-insensitivity
	fetchedUser, err = store.GetUserByEmail(context.Background(), user.Email)
	assert.NoError(t, err)
	assert.Equal(t, user.Id, fetchedUser.User.Id)

	// Test non-existent email
	_, err = store.GetUserByEmail(context.Background(), "nonexistent@example.com")
	assert.Error(t, err)
}

func TestCreateDuplicateUser(t *testing.T) {
	db, cleanup := setupTestDB(t)
	defer cleanup()

	store := NewBadgerUserStore(db)

	user := createTestUser()

	err := store.CreateUser(context.Background(), &authv1.AuthUser{
		HashedPassword: "password123",
		User:           user,
	})
	require.NoError(t, err)

	// Attempt to create user with same ID
	err = store.CreateUser(context.Background(), &authv1.AuthUser{
		HashedPassword: "password123",
		User:           user,
	})
	assert.Error(t, err)

	// Attempt to create user with same email but different ID
	userWithSameEmail := createSecondTestUser()
	userWithSameEmail.Email = user.Email
	err = store.CreateUser(context.Background(), &authv1.AuthUser{
		HashedPassword: "password123",
		User:           userWithSameEmail,
	})
	assert.Error(t, err)
}

func TestUpdateUser(t *testing.T) {
	db, cleanup := setupTestDB(t)
	defer cleanup()

	store := NewBadgerUserStore(db)

	// Create initial user
	user := createTestUser()
	err := store.CreateUser(context.Background(), &authv1.AuthUser{
		HashedPassword: "password123",
		User:           user,
	})
	require.NoError(t, err)

	t.Run("UpdateExistingUser", func(t *testing.T) {
		updatedUser := &authv1.AuthUser{
			HashedPassword: "newpassword456",
			User: &userv1.User{
				Id:    user.Id,
				Email: user.Email,
				Name:  "Updated Name",
			},
		}

		err := store.UpdateUser(context.Background(), updatedUser)
		assert.NoError(t, err)

		fetchedUser, err := store.GetUserById(context.Background(), user.Id)
		assert.NoError(t, err)
		assert.Equal(t, "Updated Name", fetchedUser.User.Name)
		assert.Equal(t, "newpassword456", fetchedUser.HashedPassword)
	})

	t.Run("UpdateNonExistentUser", func(t *testing.T) {
		nonExistentUser := &authv1.AuthUser{
			User: &userv1.User{
				Id:    "non-existent-id",
				Email: "nonexistent@example.com",
			},
		}

		err := store.UpdateUser(context.Background(), nonExistentUser)
		assert.Error(t, err)
		assert.Contains(t, err.Error(), "user with ID non-existent-id not found")
	})

	t.Run("UpdateUserEmail", func(t *testing.T) {
		updatedUser := &authv1.AuthUser{
			HashedPassword: "password123",
			User: &userv1.User{
				Id:    user.Id,
				Email: "newemail@example.com",
				Name:  user.Name,
			},
		}

		err := store.UpdateUser(context.Background(), updatedUser)
		assert.NoError(t, err)

		fetchedUser, err := store.GetUserByEmail(context.Background(), "newemail@example.com")
		assert.NoError(t, err)
		assert.Equal(t, user.Id, fetchedUser.User.Id)

		_, err = store.GetUserByEmail(context.Background(), user.Email)
		assert.Error(t, err, "Old email should not exist")
	})

	t.Run("UpdateToExistingEmail", func(t *testing.T) {
		// Create a second user
		secondUser := createSecondTestUser()
		err := store.CreateUser(context.Background(), &authv1.AuthUser{
			HashedPassword: "password789",
			User:           secondUser,
		})
		require.NoError(t, err)

		// Try to update the first user's email to the second user's email
		updatedUser := &authv1.AuthUser{
			HashedPassword: "password123",
			User: &userv1.User{
				Id:    user.Id,
				Email: secondUser.Email,
				Name:  user.Name,
			},
		}

		err = store.UpdateUser(context.Background(), updatedUser)
		assert.Error(t, err)
		assert.Contains(t, err.Error(), "user with email second-user@example.com already exists")
	})

	t.Run("UpdateEmailCaseInsensitive", func(t *testing.T) {
		updatedUser := &authv1.AuthUser{
			HashedPassword: "password123",
			User: &userv1.User{
				Id:    user.Id,
				Email: "NeWeMaIl@ExAmPlE.cOm",
				Name:  user.Name,
			},
		}

		err := store.UpdateUser(context.Background(), updatedUser)
		assert.NoError(t, err)

		fetchedUser, err := store.GetUserByEmail(context.Background(), "newemail@example.com")
		assert.NoError(t, err)
		assert.Equal(t, user.Id, fetchedUser.User.Id)
	})
}
