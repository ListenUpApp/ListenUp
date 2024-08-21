package store

import (
	libraryv1 "buf.build/gen/go/listenup/listenup/protocolbuffers/go/listenup/library/v1"
	"github.com/dgraph-io/badger/v4"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"google.golang.org/protobuf/types/known/timestamppb"
	"testing"
	"time"
)

type testDBWrapper struct {
	*badger.DB
}

func (w *testDBWrapper) View(fn func(txn *badger.Txn) error) error {
	return w.DB.View(fn)
}

func (w *testDBWrapper) Update(fn func(txn *badger.Txn) error) error {
	return w.DB.Update(fn)
}

func TestCreateLibrary(t *testing.T) {
	db, cleanup := setupTestDB(t)
	defer cleanup()

	store := NewBadgerLibraryStore(&testDBWrapper{db})

	testCases := []struct {
		name        string
		library     *libraryv1.Library
		expectError bool
	}{
		{
			name: "Valid Library",
			library: &libraryv1.Library{
				Id:        "library-id",
				Name:      "Test Library",
				CreatedAt: timestamppb.New(time.Now()),
			},
			expectError: false,
		},
		{
			name: "Duplicate Library ID",
			library: &libraryv1.Library{
				Id:        "duplicate-id",
				Name:      "Duplicate Library",
				CreatedAt: timestamppb.New(time.Now()),
			},
			expectError: false, // First insertion
		},
		{
			name: "Duplicate Library ID (should fail)",
			library: &libraryv1.Library{
				Id:        "duplicate-id",
				Name:      "Another Duplicate Library",
				CreatedAt: timestamppb.New(time.Now()),
			},
			expectError: true, // Second insertion with same ID should fail
		},
	}

	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			err := store.CreateLibrary(tc.library)
			if tc.expectError {
				assert.Error(t, err)
				assert.Contains(t, err.Error(), "already exists")
			} else {
				assert.NoError(t, err)
			}
		})
	}
}

func TestBadgerLibraryStore_GetLibraryByID(t *testing.T) {
	db, cleanup := setupTestDB(t)
	defer cleanup()

	store := NewBadgerLibraryStore(&testDBWrapper{db})

	// Create a test library
	testLibrary := &libraryv1.Library{
		Id:        "library-id",
		Name:      "Test Library",
		CreatedAt: timestamppb.New(time.Now()),
	}
	err := store.CreateLibrary(testLibrary)
	require.NoError(t, err)

	testCases := []struct {
		name          string
		id            string
		expectLibrary *libraryv1.Library
		expectError   bool
	}{
		{
			name:          "Existing Library",
			id:            testLibrary.Id,
			expectLibrary: testLibrary,
			expectError:   false,
		},
		{
			name:          "Non-existent Library",
			id:            "non-existent-id",
			expectLibrary: nil,
			expectError:   true,
		},
	}

	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			library, err := store.GetLibraryByID(tc.id)
			if tc.expectError {
				assert.Error(t, err)
				assert.Nil(t, library)
			} else {
				assert.NoError(t, err)
				assert.NotNil(t, library)
				assert.Equal(t, tc.expectLibrary.Id, library.Id)
				assert.Equal(t, tc.expectLibrary.Name, library.Name)
				assert.Equal(t, tc.expectLibrary.CreatedAt.AsTime().Unix(), library.CreatedAt.AsTime().Unix())
			}
		})
	}
}

func TestBadgerLibraryStore_CreateAndGetLibrary(t *testing.T) {
	db, cleanup := setupTestDB(t)
	defer cleanup()

	store := NewBadgerLibraryStore(&testDBWrapper{db})

	// Create a new library
	newLibrary := &libraryv1.Library{
		Id:        "library-id",
		Name:      "New Test Library",
		CreatedAt: timestamppb.New(time.Now()),
	}

	// Test CreateLibrary
	err := store.CreateLibrary(newLibrary)
	assert.NoError(t, err)

	// Test GetLibraryByID
	retrievedLibrary, err := store.GetLibraryByID(newLibrary.Id)
	assert.NoError(t, err)
	assert.NotNil(t, retrievedLibrary)
	assert.Equal(t, newLibrary.Id, retrievedLibrary.Id)
	assert.Equal(t, newLibrary.Name, retrievedLibrary.Name)
	assert.Equal(t, newLibrary.CreatedAt.AsTime().Unix(), retrievedLibrary.CreatedAt.AsTime().Unix())
}
