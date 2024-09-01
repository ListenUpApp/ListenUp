package store

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"github.com/ListenUpApp/ListenUp/internal/db"
	"github.com/ListenUpApp/ListenUp/internal/logger"
	"github.com/dgraph-io/badger/v4"
	"time"
)

type AuthStore interface {
	StoreRefreshToken(ctx context.Context, userID, token string) error
	GetRefreshToken(ctx context.Context, userID string) (string, error)
	UpdateRefreshToken(ctx context.Context, userID, newToken string) error
	DeleteRefreshToken(ctx context.Context, userID string) error
	CleanupExpiredTokens(ctx context.Context, expirationTime time.Duration) error
}

var ErrTokenNotFound = errors.New("token not found")

type BadgerAuthStore struct {
	db db.DBInterface
}

func NewBadgerAuthStore(db db.DBInterface) *BadgerAuthStore {
	return &BadgerAuthStore{db: db}
}

type TokenInfo struct {
	UserID    string    `json:"user_id"`
	Token     string    `json:"token"`
	CreatedAt time.Time `json:"created_at"`
}

func (s *BadgerAuthStore) StoreRefreshToken(ctx context.Context, userID, token string) error {
	tokenInfo := TokenInfo{
		UserID:    userID,
		Token:     token,
		CreatedAt: time.Now(),
	}

	data, err := json.Marshal(tokenInfo)

	if err != nil {
		logger.Error("Failed to marshal token info", "Error", err)
		return fmt.Errorf("failed to marshal token info: %w", err)
	}

	return s.db.Update(func(txn *badger.Txn) error {
		key := []byte(fmt.Sprintf("refresh_token:%s", userID))
		return txn.Set(key, data)
	})
}

func (s *BadgerAuthStore) GetRefreshToken(ctx context.Context, userID string) (string, error) {
	var tokenInfo TokenInfo

	err := s.db.View(func(txn *badger.Txn) error {
		key := []byte(fmt.Sprintf("refresh_token:%s", userID))
		item, err := txn.Get(key)
		if err != nil {
			if errors.Is(err, badger.ErrKeyNotFound) {
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

func (s *BadgerAuthStore) UpdateRefreshToken(ctx context.Context, userID, newToken string) error {
	return s.StoreRefreshToken(ctx, userID, newToken) // In BadgerDB, storing overwrites existing value
}

func (s *BadgerAuthStore) DeleteRefreshToken(ctx context.Context, userID string) error {
	return s.db.Update(func(txn *badger.Txn) error {
		key := []byte(fmt.Sprintf("refresh_token:%s", userID))
		return txn.Delete(key)
	})
}

func (s *BadgerAuthStore) CleanupExpiredTokens(ctx context.Context, expirationTime time.Duration) error {
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
