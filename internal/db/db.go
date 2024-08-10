package db

import "github.com/dgraph-io/badger/v4"

type DBInterface interface {
	Close() error
	Update(func(txn *badger.Txn) error) error
	View(func(txn *badger.Txn) error) error
	NewTransaction(update bool) *badger.Txn
}

var DB DBInterface

type DBOpener func(opt badger.Options) (DBInterface, error)

func InitDB(path string, opener DBOpener) (DBInterface, error) {
	if opener == nil {
		opener = func(opt badger.Options) (DBInterface, error) {
			return badger.Open(opt)
		}
	}
	options := badger.DefaultOptions(path)
	db, err := opener(options)
	if err != nil {
		return nil, err
	}
	DB = db
	return db, nil
}

func CloseDB() error {
	if DB != nil {
		return DB.Close()
	}
	return nil
}
