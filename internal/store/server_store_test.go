package store

import (
	authv1 "buf.build/gen/go/listenup/listenup/protocolbuffers/go/listenup/auth/v1"
	serverv1 "buf.build/gen/go/listenup/listenup/protocolbuffers/go/listenup/server/v1"
	"context"
	"github.com/dgraph-io/badger/v4"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"
	"google.golang.org/protobuf/proto"
	"testing"
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

// MockTxn is a mock implementation of badger.Txn
type MockTxn struct {
	mock.Mock
}

func (m *MockTxn) Get(key []byte) (*badger.Item, error) {
	args := m.Called(key)
	return args.Get(0).(*badger.Item), args.Error(1)
}

func (m *MockTxn) Set(key, val []byte) error {
	args := m.Called(key, val)
	return args.Error(0)
}

func (m *MockTxn) Commit() error {
	args := m.Called()
	return args.Error(0)
}

func (m *MockTxn) Discard() {
	m.Called()
}

// MockItem is a mock implementation of badger.Item
type MockItem struct {
	mock.Mock
}

func (m *MockItem) Key() []byte {
	args := m.Called()
	return args.Get(0).([]byte)
}

func (m *MockItem) Value(fn func(val []byte) error) error {
	args := m.Called(fn)
	return args.Error(0)
}

func TestCreateServer(t *testing.T) {
	mockDB := new(MockDB)
	store := NewBadgerServerStore(mockDB)

	t.Run("Create server successfully", func(t *testing.T) {
		mockTxn := new(MockTxn)
		mockDB.On("NewTransaction", true).Return(mockTxn)
		mockTxn.On("Get", []byte(authServerKey)).Return((*badger.Item)(nil), badger.ErrKeyNotFound)
		mockTxn.On("Set", []byte(authServerKey), mock.Anything).Return(nil).
			Run(func(args mock.Arguments) {
				data := args.Get(1).([]byte)
				var createdServer authv1.AuthServer
				err := proto.Unmarshal(data, &createdServer)
				assert.NoError(t, err)
				assert.NotEmpty(t, createdServer.JwtSigningToken)
				assert.False(t, createdServer.Server.IsSetUp)
				assert.NotNil(t, createdServer.Server.Config)
			})
		mockTxn.On("Commit").Return(nil)

		err := store.CreateServer(context.Background())
		assert.NoError(t, err)
		mockDB.AssertExpectations(t)
		mockTxn.AssertExpectations(t)
	})

	t.Run("Fail to create server when it already exists", func(t *testing.T) {
		mockTxn := new(MockTxn)
		mockDB.On("NewTransaction", true).Return(mockTxn)
		mockTxn.On("Get", []byte(authServerKey)).Return(new(MockItem), nil)
		mockTxn.On("Discard").Return()

		err := store.CreateServer(context.Background())
		assert.Error(t, err)
		assert.Equal(t, "server already exists in the database", err.Error())
		mockDB.AssertExpectations(t)
		mockTxn.AssertExpectations(t)
	})
}

func TestGetServer(t *testing.T) {
	mockDB := new(MockDB)
	store := NewBadgerServerStore(mockDB)

	t.Run("Get server successfully", func(t *testing.T) {
		expectedServer := &authv1.AuthServer{
			JwtSigningToken: "test-token",
			Server: &serverv1.Server{
				IsSetUp: true,
				Config:  &serverv1.ServerConfig{},
			},
		}
		serverData, _ := proto.Marshal(expectedServer)

		mockTxn := new(MockTxn)
		mockDB.On("NewTransaction", false).Return(mockTxn)
		mockItem := new(MockItem)
		mockTxn.On("Get", []byte(authServerKey)).Return(mockItem, nil)
		mockItem.On("Value", mock.AnythingOfType("func([]byte) error")).
			Return(nil).
			Run(func(args mock.Arguments) {
				valueFn := args.Get(0).(func([]byte) error)
				valueFn(serverData)
			})
		mockTxn.On("Discard").Return()

		server, err := store.GetServer(context.Background())
		assert.NoError(t, err)
		assert.Equal(t, expectedServer.JwtSigningToken, server.JwtSigningToken)
		assert.Equal(t, expectedServer.Server.IsSetUp, server.Server.IsSetUp)
		mockDB.AssertExpectations(t)
		mockTxn.AssertExpectations(t)
	})

	t.Run("Fail to get server when it doesn't exist", func(t *testing.T) {
		mockTxn := new(MockTxn)
		mockDB.On("NewTransaction", false).Return(mockTxn)
		mockTxn.On("Get", []byte(authServerKey)).Return((*badger.Item)(nil), badger.ErrKeyNotFound)
		mockTxn.On("Discard").Return()

		server, err := store.GetServer(context.Background())
		assert.Error(t, err)
		assert.Nil(t, server)
		assert.Equal(t, badger.ErrKeyNotFound, err)
		mockDB.AssertExpectations(t)
		mockTxn.AssertExpectations(t)
	})
}

func TestUpdateServer(t *testing.T) {
	mockDB := new(MockDB)
	store := NewBadgerServerStore(mockDB)

	t.Run("Update server successfully", func(t *testing.T) {
		initialServer := &authv1.AuthServer{
			JwtSigningToken: "initial-token",
			Server: &serverv1.Server{
				IsSetUp: false,
				Config:  &serverv1.ServerConfig{},
			},
		}
		updatedServer := &authv1.AuthServer{
			JwtSigningToken: "initial-token",
			Server: &serverv1.Server{
				IsSetUp: true,
				Config:  &serverv1.ServerConfig{},
			},
		}
		initialData, _ := proto.Marshal(initialServer)
		updatedData, _ := proto.Marshal(updatedServer)

		mockTxn := new(MockTxn)
		mockDB.On("NewTransaction", true).Return(mockTxn)
		mockItem := new(MockItem)
		mockTxn.On("Get", []byte(authServerKey)).Return(mockItem, nil)
		mockItem.On("Value", mock.AnythingOfType("func([]byte) error")).
			Return(nil).
			Run(func(args mock.Arguments) {
				valueFn := args.Get(0).(func([]byte) error)
				valueFn(initialData)
			})
		mockTxn.On("Set", []byte(authServerKey), mock.Anything).Return(nil).
			Run(func(args mock.Arguments) {
				assert.Equal(t, updatedData, args.Get(1))
			})
		mockTxn.On("Commit").Return(nil)

		err := store.UpdateServer(context.Background(), func(server *authv1.AuthServer) error {
			server.Server.IsSetUp = true
			return nil
		})

		assert.NoError(t, err)
		mockDB.AssertExpectations(t)
		mockTxn.AssertExpectations(t)
	})

	t.Run("Fail to update server when it doesn't exist", func(t *testing.T) {
		mockTxn := new(MockTxn)
		mockDB.On("NewTransaction", true).Return(mockTxn)
		mockTxn.On("Get", []byte(authServerKey)).Return((*badger.Item)(nil), badger.ErrKeyNotFound)
		mockTxn.On("Discard").Return()

		err := store.UpdateServer(context.Background(), func(server *authv1.AuthServer) error {
			return nil
		})

		assert.Error(t, err)
		assert.Equal(t, "AuthServer does not exist in the database", err.Error())
		mockDB.AssertExpectations(t)
		mockTxn.AssertExpectations(t)
	})
}
