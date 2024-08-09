package db

import (
	authv1 "buf.build/gen/go/listenup/listenup/protocolbuffers/go/listenup/auth/v1"
	"context"
	"errors"
	"fmt"
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

type GopherUserStore struct {
	db DBInterface
}

func NewGopherUserStore(db DBInterface) *GopherUserStore {
	return &GopherUserStore{
		db: db,
	}
}

func (g *GopherUserStore) CreateUser(ctx context.Context, user *authv1.AuthUser) error {
	return g.db.Update(func(txn *badger.Txn) error {
		// Check if user with the same ID already exists
		userKey := []byte(userPrefix + user.User.Id)
		_, err := txn.Get(userKey)
		if err == nil {
			return fmt.Errorf("user with ID %s already exists", user.User.Id)
		} else if !errors.Is(badger.ErrKeyNotFound, err) {
			return fmt.Errorf("error checking for existing user: %w", err)
		}

		// Check if user with the same email already exists
		emailPrefix := []byte(emailIndex + strings.ToLower(user.User.Email) + emailSeparator)
		it := txn.NewIterator(badger.DefaultIteratorOptions)
		defer it.Close()

		for it.Seek(emailPrefix); it.ValidForPrefix(emailPrefix); it.Next() {
			return fmt.Errorf("user with email %s already exists", user.User.Email)
		}

		// If we've reached here, both ID and email are unique
		userBytes, err := proto.Marshal(user.User)
		if err != nil {
			return fmt.Errorf("failed to marshal user: %w", err)
		}

		if err := txn.Set(userKey, userBytes); err != nil {
			return fmt.Errorf("failed to set user in BadgerDB: %w", err)
		}

		// Create email index
		emailKey := append(emailPrefix, []byte(user.User.Id)...)
		if err := txn.Set(emailKey, nil); err != nil {
			return fmt.Errorf("failed to set email index in BadgerDB: %w", err)
		}

		return nil
	})
}

func (g *GopherUserStore) GetUserById(ctx context.Context, id string) (*authv1.AuthUser, error) {
	var user authv1.AuthUser

	err := g.db.View(func(txn *badger.Txn) error {
		item, err := txn.Get([]byte(userPrefix + id))
		if err != nil {
			return fmt.Errorf("failed to get user from BadgerDB: %w", err)
		}

		return item.Value(func(val []byte) error {
			return proto.Unmarshal(val, &user)
		})
	})

	if err != nil {
		return nil, err
	}

	return &user, nil
}

func (g *GopherUserStore) GetUserByEmail(ctx context.Context, email string) (*authv1.AuthUser, error) {
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
		return fmt.Errorf("user not found for email: %s", email)
	})

	if err != nil {
		return nil, err
	}
	return g.GetUserById(ctx, userID)
}
