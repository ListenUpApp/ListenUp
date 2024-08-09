package db

import (
	"context"
	"encoding/json"
	"testing"
	"time"

	"github.com/dgraph-io/badger/v4"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestStoreRefreshToken(t *testing.T) {
	db, cleanup := setupTestDB(t)
	defer cleanup()

	store := NewAuthStore(db)
	ctx := context.Background()

	err := store.StoreRefreshToken(ctx, "user1", "token123")
	assert.NoError(t, err)

	// Verify the token was stored
	token, err := store.GetRefreshToken(ctx, "user1")
	assert.NoError(t, err)
	assert.Equal(t, "token123", token)
}

func TestGetRefreshToken(t *testing.T) {
	db, cleanup := setupTestDB(t)
	defer cleanup()

	store := NewAuthStore(db)
	ctx := context.Background()

	// Store a token
	err := store.StoreRefreshToken(ctx, "user2", "token456")
	require.NoError(t, err)

	// Retrieve the token
	token, err := store.GetRefreshToken(ctx, "user2")
	assert.NoError(t, err)
	assert.Equal(t, "token456", token)

	// Try to get a non-existent token
	_, err = store.GetRefreshToken(ctx, "nonexistent")
	assert.Equal(t, ErrTokenNotFound, err)
}

func TestUpdateRefreshToken(t *testing.T) {
	db, cleanup := setupTestDB(t)
	defer cleanup()

	store := NewAuthStore(db)
	ctx := context.Background()

	// Store initial token
	err := store.StoreRefreshToken(ctx, "user3", "initialToken")
	require.NoError(t, err)

	// Update the token
	err = store.UpdateRefreshToken(ctx, "user3", "updatedToken")
	assert.NoError(t, err)

	// Verify the token was updated
	token, err := store.GetRefreshToken(ctx, "user3")
	assert.NoError(t, err)
	assert.Equal(t, "updatedToken", token)
}

func TestDeleteRefreshToken(t *testing.T) {
	db, cleanup := setupTestDB(t)
	defer cleanup()

	store := NewAuthStore(db)
	ctx := context.Background()

	// Store a token
	err := store.StoreRefreshToken(ctx, "user4", "tokenToDelete")
	require.NoError(t, err)

	// Delete the token
	err = store.DeleteRefreshToken(ctx, "user4")
	assert.NoError(t, err)

	// Verify the token was deleted
	_, err = store.GetRefreshToken(ctx, "user4")
	assert.Equal(t, ErrTokenNotFound, err)
}

func TestCleanupExpiredTokens(t *testing.T) {
	db, cleanup := setupTestDB(t)
	defer cleanup()

	store := NewAuthStore(db)
	ctx := context.Background()

	// Store tokens with different creation times
	store.db.Update(func(txn *badger.Txn) error {
		oldToken := TokenInfo{UserID: "user5", Token: "oldToken", CreatedAt: time.Now().Add(-2 * time.Hour)}
		newToken := TokenInfo{UserID: "user6", Token: "newToken", CreatedAt: time.Now()}

		oldData, _ := json.Marshal(oldToken)
		newData, _ := json.Marshal(newToken)

		txn.Set([]byte("refresh_token:user5"), oldData)
		txn.Set([]byte("refresh_token:user6"), newData)
		return nil
	})

	// Cleanup tokens older than 1 hour
	err := store.CleanupExpiredTokens(ctx, 1*time.Hour)
	assert.NoError(t, err)

	// Verify old token was deleted and new token remains
	_, err = store.GetRefreshToken(ctx, "user5")
	assert.Equal(t, ErrTokenNotFound, err)

	token, err := store.GetRefreshToken(ctx, "user6")
	assert.NoError(t, err)
	assert.Equal(t, "newToken", token)
}
