package store

import (
	authv1 "buf.build/gen/go/listenup/listenup/protocolbuffers/go/listenup/auth/v1"
	serverv1 "buf.build/gen/go/listenup/listenup/protocolbuffers/go/listenup/server/v1"
	"context"
	"errors"
	"fmt"
	"github.com/ListenUpApp/ListenUp/internal/db"
	"github.com/ListenUpApp/ListenUp/internal/logger"
	"github.com/dgraph-io/badger/v4"
	gonanoid "github.com/matoous/go-nanoid/v2"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
	"google.golang.org/protobuf/proto"
)

type ServerStore interface {
	GetServer(ctx context.Context) (*authv1.AuthServer, error)
	CreateServer(ctx context.Context) error
	UpdateServer(ctx context.Context, updateFunc func(*authv1.AuthServer) error) error
}

type BadgerServerStore struct {
	db db.DBInterface
}

func NewBadgerServerStore(db db.DBInterface) ServerStore {
	return &BadgerServerStore{db: db}
}

const authServerKey = "server"

func (s *BadgerServerStore) CreateServer(ctx context.Context) error {
	logger.Info("Attempting to create a new server")
	return s.db.Update(func(txn *badger.Txn) error {
		// Check if the AuthServer already exists
		_, err := txn.Get([]byte(authServerKey))
		if err == nil {
			logger.Error("Attempted to create a new server instance, but one already exists")
			return errors.New("server already exists in the database")
		}
		if !errors.Is(err, badger.ErrKeyNotFound) {
			logger.Error("Unexpected error checking for existing server", "error", err)
			return fmt.Errorf("unexpected error checking for existing server: %w", err)
		}

		tokenHash, err := gonanoid.Generate("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz", 8)
		if err != nil {
			logger.Error("Could not generate a tokenHash with NanoID", "error", err)
			return status.Error(codes.Internal, "Could not generate unique signing token")
		}

		newAuthServer := &authv1.AuthServer{
			Server: &serverv1.Server{
				IsSetUp: false,
				Config:  &serverv1.ServerConfig{},
			},
			JwtSigningToken: tokenHash,
		}

		data, err := proto.Marshal(newAuthServer)
		if err != nil {
			logger.Error("Failed to marshal new server", "error", err)
			return fmt.Errorf("failed to marshal new server: %w", err)
		}

		if err := txn.Set([]byte(authServerKey), data); err != nil {
			logger.Error("Failed to set new server in database", "error", err)
			return fmt.Errorf("failed to set new server in database: %w", err)
		}

		logger.Info("Server created successfully")
		return nil
	})
}

func (s *BadgerServerStore) GetServer(ctx context.Context) (*authv1.AuthServer, error) {
	logger.Info("Attempting to get server")
	var authServer authv1.AuthServer

	err := s.db.View(func(txn *badger.Txn) error {
		item, err := txn.Get([]byte(authServerKey))
		if err != nil {
			if errors.Is(err, badger.ErrKeyNotFound) {
				logger.Info("No server found in the database")
				return nil // Server not found, but not an error
			}
			logger.Error("Failed to get server from database", "error", err)
			return fmt.Errorf("failed to get server from database: %w", err)
		}

		return item.Value(func(val []byte) error {
			return proto.Unmarshal(val, &authServer)
		})
	})

	if err != nil {
		return nil, err
	}

	if authServer.Server == nil {
		logger.Info("No server found in the database")
		return nil, nil
	}

	logger.Info("Server retrieved successfully")
	return &authServer, nil
}

func (s *BadgerServerStore) UpdateServer(ctx context.Context, updateFunc func(*authv1.AuthServer) error) error {
	logger.Info("Attempting to update server")
	return s.db.Update(func(txn *badger.Txn) error {
		// Get the existing AuthServer
		item, err := txn.Get([]byte(authServerKey))
		if err != nil {
			if errors.Is(err, badger.ErrKeyNotFound) {
				logger.Error("Attempted to update non-existent server")
				return errors.New("AuthServer does not exist in the database")
			}
			logger.Error("Failed to get server for update", "error", err)
			return fmt.Errorf("failed to get server for update: %w", err)
		}

		var authServer authv1.AuthServer
		err = item.Value(func(val []byte) error {
			return proto.Unmarshal(val, &authServer)
		})
		if err != nil {
			logger.Error("Failed to unmarshal server data", "error", err)
			return fmt.Errorf("failed to unmarshal server data: %w", err)
		}

		// Apply the update function
		if err := updateFunc(&authServer); err != nil {
			logger.Error("Update function failed", "error", err)
			return fmt.Errorf("update function failed: %w", err)
		}

		// Serialize the updated AuthServer struct
		updatedData, err := proto.Marshal(&authServer)
		if err != nil {
			logger.Error("Failed to marshal updated server", "error", err)
			return fmt.Errorf("failed to marshal updated server: %w", err)
		}

		// Write the updated data back to BadgerDB
		if err := txn.Set([]byte(authServerKey), updatedData); err != nil {
			logger.Error("Failed to write updated server to database", "error", err)
			return fmt.Errorf("failed to write updated server to database: %w", err)
		}

		logger.Info("Server updated successfully")
		return nil
	})
}
