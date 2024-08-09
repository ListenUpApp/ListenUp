package db

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"github.com/dgraph-io/badger/v4"
	"time"
)

// ErrTokenNotFound is returned when a token is not found in the database
var ErrTokenNotFound = errors.New("token not found")

// AuthStore handles all database operations related to authentication
type AuthStore struct {
	db *badger.DB
}

// NewAuthStore creates a new AuthStore
func NewAuthStore(db *badger.DB) *AuthStore {
	return &AuthStore{db: db}
}

// TokenInfo stores information about a token
type TokenInfo struct {
	UserID    string    `json:"user_id"`
	Token     string    `json:"token"`
	CreatedAt time.Time `json:"created_at"`
}

// StoreRefreshToken stores a refresh token for a user
func (s *AuthStore) StoreRefreshToken(ctx context.Context, userID, token string) error {
	tokenInfo := TokenInfo{
		UserID:    userID,
		Token:     token,
		CreatedAt: time.Now(),
	}

	data, err := json.Marshal(tokenInfo)
	if err != nil {
		return fmt.Errorf("failed to marshal token info: %w", err)
	}

	return s.db.Update(func(txn *badger.Txn) error {
		key := []byte(fmt.Sprintf("refresh_token:%s", userID))
		return txn.Set(key, data)
	})
}

// GetRefreshToken retrieves the refresh token for a user
func (s *AuthStore) GetRefreshToken(ctx context.Context, userID string) (string, error) {
	var tokenInfo TokenInfo

	err := s.db.View(func(txn *badger.Txn) error {
		key := []byte(fmt.Sprintf("refresh_token:%s", userID))
		item, err := txn.Get(key)
		if err != nil {
			if err == badger.ErrKeyNotFound {
				return ErrTokenNotFound
			}
			return err
		}

		return item.Value(func(val []byte) error {
			return json.Unmarshal(val, &tokenInfo)
		})
	})

	if err != nil {
		return "", err
	}

	return tokenInfo.Token, nil
}

// UpdateRefreshToken updates the refresh token for a user
func (s *AuthStore) UpdateRefreshToken(ctx context.Context, userID, newToken string) error {
	return s.StoreRefreshToken(ctx, userID, newToken) // In BadgerDB, storing overwrites existing value
}

// DeleteRefreshToken deletes the refresh token for a user
func (s *AuthStore) DeleteRefreshToken(ctx context.Context, userID string) error {
	return s.db.Update(func(txn *badger.Txn) error {
		key := []byte(fmt.Sprintf("refresh_token:%s", userID))
		return txn.Delete(key)
	})
}

// CleanupExpiredTokens removes expired refresh tokens
func (s *AuthStore) CleanupExpiredTokens(ctx context.Context, expirationTime time.Duration) error {
	return s.db.Update(func(txn *badger.Txn) error {
		opts := badger.DefaultIteratorOptions
		opts.PrefetchValues = false
		it := txn.NewIterator(opts)
		defer it.Close()

		prefix := []byte("refresh_token:")
		for it.Seek(prefix); it.ValidForPrefix(prefix); it.Next() {
			item := it.Item()
			k := item.Key()
			err := item.Value(func(v []byte) error {
				var tokenInfo TokenInfo
				if err := json.Unmarshal(v, &tokenInfo); err != nil {
					return err
				}
				if time.Since(tokenInfo.CreatedAt) > expirationTime {
					return txn.Delete(k)
				}
				return nil
			})
			if err != nil {
				return err
			}
		}
		return nil
	})
}
