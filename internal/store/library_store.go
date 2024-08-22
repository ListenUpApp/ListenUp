package store

import (
	libraryv1 "buf.build/gen/go/listenup/listenup/protocolbuffers/go/listenup/library/v1"
	"errors"
	"fmt"
	"github.com/ListenUpApp/ListenUp/internal/db"
	"github.com/dgraph-io/badger/v4"
	"google.golang.org/protobuf/proto"
)

type LibraryStore interface {
	CreateLibrary(library *libraryv1.Library) error
	GetLibraryByID(id string) (*libraryv1.Library, error)
	AddDirectory(libraryID string, directory *libraryv1.Directory) error
	GetAllLibraries() ([]*libraryv1.Library, error)
	GetLibrariesByIDs(ids []string) ([]*libraryv1.Library, error)
}

type BadgerLibraryStore struct {
	db db.DBInterface
}

func NewBadgerLibraryStore(dbInstance db.DBInterface) LibraryStore {
	return &BadgerLibraryStore{db: dbInstance}
}

const (
	keyPrefixLibrary      = "library:"
	keyPrefixIndexLibrary = "index:library:"
	keyPrefixUserLibrary  = "user:library:"
)

func (s *BadgerLibraryStore) CreateLibrary(library *libraryv1.Library) error {
	return s.db.Update(func(txn *badger.Txn) error {
		// Check if library with this ID already exists
		key := []byte(keyPrefixLibrary + library.Id)
		_, err := txn.Get(key)
		if err == nil {
			return fmt.Errorf("library with ID %s already exists", library.Id)
		} else if !errors.Is(badger.ErrKeyNotFound, err) {
			return fmt.Errorf("error checking for existing library: %v", err)
		}

		// If we're here, the library doesn't exist, so we can create it
		data, err := proto.Marshal(library)
		if err != nil {
			return fmt.Errorf("failed to marshal library: %v", err)
		}

		if err := txn.Set(key, data); err != nil {
			return err
		}

		return nil
	})
}

func (s *BadgerLibraryStore) GetAllLibraries() ([]*libraryv1.Library, error) {
	var libraries []*libraryv1.Library

	err := s.db.View(func(txn *badger.Txn) error {
		opts := badger.DefaultIteratorOptions
		opts.Prefix = []byte(keyPrefixLibrary)
		it := txn.NewIterator(opts)
		defer it.Close()

		for it.Rewind(); it.Valid(); it.Next() {
			item := it.Item()
			var library libraryv1.Library
			err := item.Value(func(val []byte) error {
				return proto.Unmarshal(val, &library)
			})
			if err != nil {
				return fmt.Errorf("failed to unmarshal library: %v", err)
			}
			libraries = append(libraries, &library)
		}
		return nil
	})

	if err != nil {
		return nil, err
	}

	return libraries, nil
}

func (s *BadgerLibraryStore) GetLibrariesByIDs(ids []string) ([]*libraryv1.Library, error) {
	var libraries []*libraryv1.Library

	err := s.db.View(func(txn *badger.Txn) error {
		for _, id := range ids {
			item, err := txn.Get([]byte(keyPrefixLibrary + id))
			if err != nil {
				if errors.Is(badger.ErrKeyNotFound, err) {
					continue
				}
				return fmt.Errorf("error accessing library %s: %v", id, err)
			}

			var library libraryv1.Library
			err = item.Value(func(val []byte) error {
				return proto.Unmarshal(val, &library)
			})
			if err != nil {
				return fmt.Errorf("error unmarshaling library %s: %v", id, err)
			}

			libraries = append(libraries, &library)
		}
		return nil
	})

	if err != nil {
		return nil, err
	}

	return libraries, nil
}

func (s *BadgerLibraryStore) GetLibraryByID(id string) (*libraryv1.Library, error) {
	var library libraryv1.Library
	err := s.db.View(func(txn *badger.Txn) error {
		item, err := txn.Get([]byte(keyPrefixLibrary + id))
		if err != nil {
			if errors.Is(badger.ErrKeyNotFound, err) {
				return fmt.Errorf("library not found: %s", id)
			}
			return fmt.Errorf("error retrieving library: %v", err)
		}

		return item.Value(func(val []byte) error {
			return proto.Unmarshal(val, &library)
		})
	})

	if err != nil {
		return nil, err
	}

	return &library, nil
}

func (s *BadgerLibraryStore) AddDirectory(libraryID string, directory *libraryv1.Directory) error {
	return s.db.Update(func(txn *badger.Txn) error {
		// Get the existing library
		library, err := s.getLibraryByIDTxn(txn, libraryID)
		if err != nil {
			return err
		}

		// Add the new directory
		library.Directories = append(library.Directories, directory)

		// Marshal and save the updated library
		data, err := proto.Marshal(library)
		if err != nil {
			return fmt.Errorf("failed to marshal updated library: %v", err)
		}

		key := []byte(keyPrefixLibrary + libraryID)
		if err := txn.Set(key, data); err != nil {
			return fmt.Errorf("failed to save updated library: %v", err)
		}

		return nil
	})
}

func (s *BadgerLibraryStore) getLibraryByIDTxn(txn *badger.Txn, id string) (*libraryv1.Library, error) {
	var library libraryv1.Library
	item, err := txn.Get([]byte(keyPrefixLibrary + id))
	if err != nil {
		if errors.Is(badger.ErrKeyNotFound, err) {
			return nil, fmt.Errorf("library not found: %s", id)
		}
		return nil, fmt.Errorf("error retrieving library: %v", err)
	}

	err = item.Value(func(val []byte) error {
		return proto.Unmarshal(val, &library)
	})
	if err != nil {
		return nil, err
	}

	return &library, nil
}
