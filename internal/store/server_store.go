package store

import (
	authv1 "buf.build/gen/go/listenup/listenup/protocolbuffers/go/listenup/auth/v1"
	serverv1 "buf.build/gen/go/listenup/listenup/protocolbuffers/go/listenup/server/v1"
	"context"
	"errors"
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
	return s.db.Update(func(txn *badger.Txn) error {
		// Check if the AuthServer already exists
		_, err := txn.Get([]byte(authServerKey))
		if err == nil {
			logger.Error("Attempted to create a new server instance, but one already exists.")
			return errors.New("server already exists in the database")
		} else if !errors.Is(err, badger.ErrKeyNotFound) {
			logger.Info("No server found, setting up for the first time.")
			return err
		}
		tokenHash, err := gonanoid.New()
		if err != nil {
			logger.Error("Could not generate a tokenHash with NanoID", "Error", err)
			status.Errorf(codes.Internal, "Could not generate unique signing token")
		}
		newAuthServer := authv1.AuthServer{
			Server: &serverv1.Server{
				IsSetUp: false,
				Config:  &serverv1.ServerConfig{},
			},
			JwtSigningToken: tokenHash,
		}

		data, err := proto.Marshal(&newAuthServer)
		if err != nil {
			return err
		}
		logger.Info("Server created Successfully")
		return txn.Set([]byte(authServerKey), data)
	})
}

func (s *BadgerServerStore) GetServer(ctx context.Context) (*authv1.AuthServer, error) {
	var authServer authv1.AuthServer

	err := s.db.View(func(txn *badger.Txn) error {
		item, err := txn.Get([]byte(authServerKey))
		if err != nil {
			if errors.Is(badger.ErrKeyNotFound, err) {
				return err // Return ErrKeyNotFound specifically
			}
			return err
		}

		return item.Value(func(val []byte) error {
			return proto.Unmarshal(val, &authServer)
		})
	})

	if err != nil {
		if errors.Is(badger.ErrKeyNotFound, err) {
			return nil, nil
		}
		return nil, err
	}
	return &authServer, nil
}

func (s *BadgerServerStore) UpdateServer(ctx context.Context, updateFunc func(*authv1.AuthServer) error) error {
	return s.db.Update(func(txn *badger.Txn) error {
		// Get the existing AuthServer
		item, err := txn.Get([]byte(authServerKey))
		if err != nil {
			if errors.Is(err, badger.ErrKeyNotFound) {
				return errors.New("AuthServer does not exist in the database")
			}
			return err
		}

		var authServer authv1.AuthServer
		err = item.Value(func(val []byte) error {
			return proto.Unmarshal(val, &authServer)
		})
		if err != nil {
			return err
		}

		// Apply the update function
		if err := updateFunc(&authServer); err != nil {
			return err
		}

		// Serialize the updated AuthServer struct
		updatedData, err := proto.Marshal(&authServer)
		if err != nil {
			return err
		}

		// Write the updated data back to BadgerDB
		return txn.Set([]byte(authServerKey), updatedData)
	})
}
