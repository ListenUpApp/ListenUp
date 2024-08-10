package db

import (
	"errors"
	"testing"

	"github.com/dgraph-io/badger/v4"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"
)

type MockDB struct {
	mock.Mock
}

func (m *MockDB) Close() error {
	args := m.Called()
	return args.Error(0)
}

func (m *MockDB) Update(fn func(txn *badger.Txn) error) error {
	args := m.Called(fn)
	return args.Error(0)
}

func (m *MockDB) View(fn func(txn *badger.Txn) error) error {
	args := m.Called(fn)
	return args.Error(0)
}

func (m *MockDB) NewTransaction(update bool) *badger.Txn {
	args := m.Called(update)
	return args.Get(0).(*badger.Txn)
}

func TestInitDB(t *testing.T) {
	assert := assert.New(t)

	mockDB := new(MockDB)
	mockOpener := func(opt badger.Options) (DBInterface, error) {
		return mockDB, nil
	}

	db, err := InitDB("/tmp/testdb", mockOpener)
	assert.NoError(err, "InitDB should not return an error")
	assert.Equal(mockDB, db, "InitDB should return the mock database")
	assert.Equal(mockDB, DB, "Global DB variable should be set to the mock database")

	// Test with invalid path
	mockErrorOpener := func(opt badger.Options) (DBInterface, error) {
		return nil, errors.New("failed to open DB")
	}
	db, err = InitDB("/invalid/path", mockErrorOpener)
	assert.Error(err, "InitDB should return an error with invalid path")
	assert.Nil(db, "InitDB should return nil database for invalid path")
}

func TestCloseDB(t *testing.T) {
	assert := assert.New(t)

	// Save the original DB and defer its restoration
	originalDB := DB
	defer func() { DB = originalDB }()

	// Test when DB is nil
	DB = nil
	err := CloseDB()
	assert.NoError(err, "CloseDB should not return an error when DB is nil")

	// Test when DB is not nil and Close is successful
	mockDB := new(MockDB)
	mockDB.On("Close").Return(nil)
	DB = mockDB
	err = CloseDB()
	assert.NoError(err, "CloseDB should not return an error")
	mockDB.AssertExpectations(t)

	// Test when Close returns an error
	mockDB = new(MockDB)
	mockDB.On("Close").Return(errors.New("mock close error"))
	DB = mockDB
	err = CloseDB()
	assert.Error(err, "CloseDB should return an error")
	mockDB.AssertExpectations(t)
}
