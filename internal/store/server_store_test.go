package store

import (
	authv1 "buf.build/gen/go/listenup/listenup/protocolbuffers/go/listenup/auth/v1"
	serverv1 "buf.build/gen/go/listenup/listenup/protocolbuffers/go/listenup/server/v1"
	"context"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"testing"
)

func TestBadgerServerStore_CreateServer(t *testing.T) {
	testDB, cleanup := setupTestDB(t)
	defer cleanup()

	store := NewBadgerServerStore(testDB)

	t.Run("Create server successfully", func(t *testing.T) {
		err := store.CreateServer(context.Background())
		assert.NoError(t, err)
	})

	t.Run("Prevent creating second server", func(t *testing.T) {
		err := store.CreateServer(context.Background())
		assert.Error(t, err)
		assert.Contains(t, err.Error(), "server already exists")
	})
}

func TestBadgerServerStore_GetServer(t *testing.T) {
	testDB, cleanup := setupTestDB(t)
	defer cleanup()

	store := NewBadgerServerStore(testDB)

	t.Run("Get non-existent server", func(t *testing.T) {
		server, err := store.GetServer(context.Background())
		assert.NoError(t, err)
		assert.Nil(t, server)
	})

	t.Run("Get existing server", func(t *testing.T) {
		err := store.CreateServer(context.Background())
		require.NoError(t, err)

		server, err := store.GetServer(context.Background())
		assert.NoError(t, err)
		assert.NotNil(t, server)
		assert.False(t, server.Server.IsSetUp)
		assert.NotEmpty(t, server.JwtSigningToken)
	})
}

func TestBadgerServerStore_UpdateServer(t *testing.T) {
	testDB, cleanup := setupTestDB(t)
	defer cleanup()

	store := NewBadgerServerStore(testDB)

	t.Run("Update non-existent server", func(t *testing.T) {
		err := store.UpdateServer(context.Background(), func(server *authv1.AuthServer) error {
			return nil
		})
		assert.Error(t, err)
		assert.Contains(t, err.Error(), "AuthServer does not exist")
	})

	t.Run("Update existing server", func(t *testing.T) {
		err := store.CreateServer(context.Background())
		require.NoError(t, err)

		err = store.UpdateServer(context.Background(), func(server *authv1.AuthServer) error {
			server.Server.IsSetUp = true
			server.JwtSigningToken = "new-token"
			return nil
		})
		assert.NoError(t, err)

		// Verify the update
		server, err := store.GetServer(context.Background())
		assert.NoError(t, err)
		assert.NotNil(t, server)
		assert.True(t, server.Server.IsSetUp)
		assert.Equal(t, "new-token", server.JwtSigningToken)
	})
}

func TestBadgerServerStore_FullFlow(t *testing.T) {
	testDB, cleanup := setupTestDB(t)
	defer cleanup()

	store := NewBadgerServerStore(testDB)

	// Create server
	err := store.CreateServer(context.Background())
	require.NoError(t, err)

	// Get server
	server, err := store.GetServer(context.Background())
	assert.NoError(t, err)
	assert.NotNil(t, server)
	assert.False(t, server.Server.IsSetUp)

	// Update server
	err = store.UpdateServer(context.Background(), func(server *authv1.AuthServer) error {
		server.Server.IsSetUp = true
		server.Server.Config = &serverv1.ServerConfig{
			ServerName: "Test Server",
		}
		return nil
	})
	assert.NoError(t, err)

	// Get updated server
	updatedServer, err := store.GetServer(context.Background())
	assert.NoError(t, err)
	assert.NotNil(t, updatedServer)
	assert.True(t, updatedServer.Server.IsSetUp)
	assert.Equal(t, "Test Server", updatedServer.Server.Config.ServerName)
}
