package badger

import "github.com/dgraph-io/badger/v4"

var DB *badger.DB

func InitDB(path string) (*badger.DB, error) {
	options := badger.DefaultOptions(path)
	db, err := badger.Open(options)
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
