package store

import (
	authv1 "buf.build/gen/go/listenup/listenup/protocolbuffers/go/listenup/auth/v1"
	"context"
	"errors"
	"fmt"
	"github.com/ListenUpApp/ListenUp/internal/db"
	"github.com/ListenUpApp/ListenUp/internal/logger"
	"github.com/dgraph-io/badger/v4"
	"google.golang.org/protobuf/proto"
	"strings"
)

type UserStore interface {
	CreateUser(ctx context.Context, user *authv1.AuthUser) error
	GetUserById(ctx context.Context, id string) (*authv1.AuthUser, error)
	GetUserByEmail(ctx context.Context, id string) (*authv1.AuthUser, error)
}

const (
	userPrefix     = "user:"
	emailIndex     = "email:"
	emailSeparator = ":"
)

type BadgerUserStore struct {
	db db.DBInterface
}

func NewBadgerUserStore(db db.DBInterface) UserStore {
	return UserStore(&BadgerUserStore{db: db})
}

func (g *BadgerUserStore) CreateUser(ctx context.Context, user *authv1.AuthUser) error {
	return g.db.Update(func(txn *badger.Txn) error {
		// Check if user with the same ID already exists
		userKey := []byte(userPrefix + user.User.Id)
		_, err := txn.Get(userKey)
		if err == nil {
			logger.Warn("A user with the ID already exists", "User ID", user.User.Id)
			return fmt.Errorf("user with ID %s already exists", user.User.Id)
		} else if !errors.Is(err, badger.ErrKeyNotFound) {
			logger.Error("error checking for existing user", "error", err)
			return fmt.Errorf("error checking for existing user: %w", err)
		}

		// Check if user with the same email already exists
		emailPrefix := []byte(emailIndex + strings.ToLower(user.User.Email) + emailSeparator)
		it := txn.NewIterator(badger.DefaultIteratorOptions)
		defer it.Close()

		for it.Seek(emailPrefix); it.ValidForPrefix(emailPrefix); it.Next() {
			logger.Warn("A user with this email already exists", "Email", user.User.Email)
			return fmt.Errorf("user with email %s already exists", user.User.Email)
		}

		// If we've reached here, both ID and email are unique
		authUserBytes, err := proto.Marshal(user)
		if err != nil {
			logger.Error("Failed to unmarshal AuthUser", "Error", err)
			return fmt.Errorf("failed to marshal AuthUser: %w", err)
		}

		if err := txn.Set(userKey, authUserBytes); err != nil {
			logger.Error("Failed to set AuthUser in BadgerDB", "Error", err)
			return fmt.Errorf("failed to set AuthUser in BadgerDB: %w", err)
		}

		// Create email index
		emailKey := append(emailPrefix, []byte(user.User.Id)...)
		if err := txn.Set(emailKey, nil); err != nil {
			logger.Error("Failed to set email index in BadgerDB", "Error", err)
			return fmt.Errorf("failed to set email index in BadgerDB: %w", err)
		}

		return nil
	})
}

func (g *BadgerUserStore) GetUserById(ctx context.Context, id string) (*authv1.AuthUser, error) {
	var authUser authv1.AuthUser

	err := g.db.View(func(txn *badger.Txn) error {
		item, err := txn.Get([]byte(userPrefix + id))
		if err != nil {
			if errors.Is(err, badger.ErrKeyNotFound) {
				logger.Warn("A user was not found with this ID", "ID", id)
				return fmt.Errorf("user not found: %w", err)
			}
			logger.Error("Failed to set email index in BadgerDB", "Error", err)
			return fmt.Errorf("failed to get user from BadgerDB: %w", err)
		}

		return item.Value(func(val []byte) error {
			if err := proto.Unmarshal(val, &authUser); err != nil {
				logger.Error("Failed to unmarshal AuthUser", "Error", err)
				return fmt.Errorf("failed to unmarshal AuthUser: %w", err)
			}
			return nil
		})
	})

	if err != nil {
		return nil, err
	}

	return &authUser, nil
}

func (g *BadgerUserStore) GetUserByEmail(ctx context.Context, email string) (*authv1.AuthUser, error) {
	var userID string
	err := g.db.View(func(txn *badger.Txn) error {
		it := txn.NewIterator(badger.DefaultIteratorOptions)
		defer it.Close()

		prefix := []byte(emailIndex + strings.ToLower(email) + emailSeparator)
		for it.Seek(prefix); it.ValidForPrefix(prefix); it.Next() {
			item := it.Item()
			k := item.Key()
			userID = string(k[len(prefix):])
			return nil // Found the first matching email, exit the loop
		}
		logger.Warn("Could not find user with the email", "Email", email)
		return fmt.Errorf("user not found for email: %s", email)
	})

	if err != nil {
		return nil, err
	}
	return g.GetUserById(ctx, userID)
}
