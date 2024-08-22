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

func TestGetLibraryByID(t *testing.T) {
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

func TestCreateAndGetLibrary(t *testing.T) {
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
func TestAddDirectory(t *testing.T) {
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

	// Test cases
	testCases := []struct {
		name        string
		libraryID   string
		directory   *libraryv1.Directory
		expectError bool
	}{
		{
			name:      "Add Directory to Existing Library",
			libraryID: testLibrary.Id,
			directory: &libraryv1.Directory{
				Id:   "library-id",
				Name: "New Directory",
				Path: "/path/to/new/directory",
			},
			expectError: false,
		},
		{
			name:      "Add Directory to Non-existent Library",
			libraryID: "non-existent-id",
			directory: &libraryv1.Directory{
				Id:   "library-id",
				Name: "Another Directory",
				Path: "/path/to/another/directory",
			},
			expectError: true,
		},
	}

	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			err := store.AddDirectory(tc.libraryID, tc.directory)
			if tc.expectError {
				assert.Error(t, err)
			} else {
				assert.NoError(t, err)

				// Verify the directory was added
				updatedLibrary, err := store.GetLibraryByID(tc.libraryID)
				assert.NoError(t, err)
				assert.NotNil(t, updatedLibrary)

				found := false
				for _, dir := range updatedLibrary.Directories {
					if dir.Id == tc.directory.Id {
						found = true
						assert.Equal(t, tc.directory.Name, dir.Name)
						assert.Equal(t, tc.directory.Path, dir.Path)
						break
					}
				}
				assert.True(t, found, "Added directory not found in the library")
			}
		})
	}
}

func TestGetLibrariesByIDs(t *testing.T) {
	db, cleanup := setupTestDB(t)
	defer cleanup()

	store := NewBadgerLibraryStore(&testDBWrapper{db})

	// Create test libraries
	libraries := []*libraryv1.Library{
		{
			Id:        "library-1",
			Name:      "Library 1",
			CreatedAt: timestamppb.New(time.Now()),
		},
		{
			Id:        "library-2",
			Name:      "Library 2",
			CreatedAt: timestamppb.New(time.Now()),
		},
		{
			Id:        "library-3",
			Name:      "Library 3",
			CreatedAt: timestamppb.New(time.Now()),
		},
	}

	for _, lib := range libraries {
		err := store.CreateLibrary(lib)
		require.NoError(t, err)
	}

	// Test cases
	testCases := []struct {
		name           string
		ids            []string
		expectedCount  int
		expectedNames  []string
		unexpectedName string
	}{
		{
			name:           "Get Specific Libraries",
			ids:            []string{libraries[0].Id, libraries[2].Id},
			expectedCount:  2,
			expectedNames:  []string{"Library 1", "Library 3"},
			unexpectedName: "Library 2",
		},
		{
			name:           "Get All Libraries",
			ids:            []string{libraries[0].Id, libraries[1].Id, libraries[2].Id},
			expectedCount:  3,
			expectedNames:  []string{"Library 1", "Library 2", "Library 3"},
			unexpectedName: "",
		},
		{
			name:           "Get Non-existent Libraries",
			ids:            []string{"non-existent-1", "non-existent-2"},
			expectedCount:  0,
			expectedNames:  []string{},
			unexpectedName: "Library 1",
		},
		{
			name:           "Mix of Existing and Non-existent Libraries",
			ids:            []string{libraries[0].Id, "non-existent", libraries[2].Id},
			expectedCount:  2,
			expectedNames:  []string{"Library 1", "Library 3"},
			unexpectedName: "Library 2",
		},
		{
			name:           "Get No Libraries",
			ids:            []string{},
			expectedCount:  0,
			expectedNames:  []string{},
			unexpectedName: "Library 1",
		},
	}

	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			result, err := store.GetLibrariesByIDs(tc.ids)
			assert.NoError(t, err)
			assert.Len(t, result, tc.expectedCount)

			for _, name := range tc.expectedNames {
				found := false
				for _, lib := range result {
					if lib.Name == name {
						found = true
						break
					}
				}
				assert.True(t, found, "Expected library not found: %s", name)
			}

			if tc.unexpectedName != "" {
				for _, lib := range result {
					assert.NotEqual(t, tc.unexpectedName, lib.Name, "Unexpected library found: %s", tc.unexpectedName)
				}
			}
		})
	}
}

func TestGetAllLibraries(t *testing.T) {
	db, cleanup := setupTestDB(t)
	defer cleanup()

	store := NewBadgerLibraryStore(&testDBWrapper{db})

	// Create test libraries
	libraries := []*libraryv1.Library{
		{
			Id:        "library-id-b",
			Name:      "Library A",
			CreatedAt: timestamppb.New(time.Now()),
		},
		{
			Id:        "library-id-a",
			Name:      "Library B",
			CreatedAt: timestamppb.New(time.Now()),
		},
	}

	for _, lib := range libraries {
		err := store.CreateLibrary(lib)
		require.NoError(t, err)
	}

	// Test GetAllLibraries
	result, err := store.GetAllLibraries()
	assert.NoError(t, err)
	assert.Len(t, result, len(libraries))

	for _, lib := range libraries {
		found := false
		for _, resultLib := range result {
			if resultLib.Id == lib.Id {
				found = true
				assert.Equal(t, lib.Name, resultLib.Name)
				break
			}
		}
		assert.True(t, found, "Library not found in results: %s", lib.Name)
	}
}
