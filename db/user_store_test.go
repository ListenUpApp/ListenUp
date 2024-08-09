package db

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

	store := NewGopherUserStore(db)

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

	store := NewGopherUserStore(db)

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

	store := NewGopherUserStore(db)

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

	store := NewGopherUserStore(db)

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
