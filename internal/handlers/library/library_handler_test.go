package library

import (
	libraryv1 "buf.build/gen/go/listenup/listenup/protocolbuffers/go/listenup/library/v1"
	"context"
	"errors"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
	"testing"
)

type MockLibraryStore struct {
	mock.Mock
}

func (m *MockLibraryStore) GetLibrariesByIDs(ids []string) ([]*libraryv1.Library, error) {
	args := m.Called()
	return args.Get(0).([]*libraryv1.Library), args.Error(1)
}

func (m *MockLibraryStore) GetLibraryByID(id string) (*libraryv1.Library, error) {
	args := m.Called(id)
	return args.Get(0).(*libraryv1.Library), args.Error(1)
}

func (m *MockLibraryStore) GetAllLibraries() ([]*libraryv1.Library, error) {
	args := m.Called()
	return args.Get(0).([]*libraryv1.Library), args.Error(1)
}

func (m *MockLibraryStore) CreateLibrary(library *libraryv1.Library) error {
	args := m.Called(library)
	return args.Error(0)
}

func (m *MockLibraryStore) AddDirectory(libraryID string, directory *libraryv1.Directory) error {
	args := m.Called(libraryID, directory)
	return args.Error(0)
}

func TestGetLibrary(t *testing.T) {
	mockStore := new(MockLibraryStore)
	handler := NewLibraryHandler(mockStore)

	testCases := []struct {
		name          string
		libraryID     string
		mockLibrary   *libraryv1.Library
		mockError     error
		expectedError bool
	}{
		{
			name:      "Successful retrieval",
			libraryID: "123",
			mockLibrary: &libraryv1.Library{
				Id:   "123",
				Name: "Test Library",
			},
			mockError:     nil,
			expectedError: false,
		},
		{
			name:          "Library not found",
			libraryID:     "456",
			mockLibrary:   nil,
			mockError:     errors.New("library not found"),
			expectedError: true,
		},
	}

	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			mockStore.On("GetLibraryByID", tc.libraryID).Return(tc.mockLibrary, tc.mockError)
			ctx := context.Background()
			req := &libraryv1.GetLibraryRequest{Id: tc.libraryID}
			resp, err := handler.GetLibrary(ctx, req)

			if tc.expectedError {
				assert.Error(t, err)
				assert.Nil(t, resp)
			} else {
				assert.NoError(t, err)
				assert.NotNil(t, resp)
				assert.Equal(t, tc.mockLibrary, resp.Library)
			}

			mockStore.AssertExpectations(t)
		})
	}
}

func TestListLibraries(t *testing.T) {
	mockStore := new(MockLibraryStore)
	handler := NewLibraryHandler(mockStore)

	testCases := []struct {
		name           string
		mockLibraries  []*libraryv1.Library
		mockError      error
		expectedError  bool
		expectedCode   codes.Code
		expectedLength int
	}{
		{
			name: "Successful retrieval",
			mockLibraries: []*libraryv1.Library{
				{Id: "1", Name: "Library 1"},
				{Id: "2", Name: "Library 2"},
			},
			mockError:      nil,
			expectedError:  false,
			expectedLength: 2,
		},
		{
			name:           "Error retrieving libraries",
			mockLibraries:  nil,
			mockError:      errors.New("database error"),
			expectedError:  true,
			expectedCode:   codes.Internal,
			expectedLength: 0,
		},
	}

	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			mockStore.On("GetAllLibraries").Return(tc.mockLibraries, tc.mockError).Once()
			ctx := context.Background()
			req := &libraryv1.ListLibrariesRequest{}
			resp, err := handler.ListLibraries(ctx, req)

			if tc.expectedError {
				assert.Error(t, err)
				assert.Nil(t, resp)
				st, ok := status.FromError(err)
				assert.True(t, ok, "Expected status.Status error, got %T", err)
				if ok {
					assert.Equal(t, tc.expectedCode, st.Code())
				}
			} else {
				assert.NoError(t, err)
				assert.NotNil(t, resp)
				assert.Len(t, resp.Libraries, tc.expectedLength)
			}

			mockStore.AssertExpectations(t)
		})
	}
}

func TestCreateLibrary(t *testing.T) {
	mockStore := new(MockLibraryStore)
	handler := NewLibraryHandler(mockStore)

	testCases := []struct {
		name          string
		request       *libraryv1.CreateLibraryRequest
		mockError     error
		expectedError bool
		expectedCode  codes.Code
	}{
		{
			name: "Successful creation",
			request: &libraryv1.CreateLibraryRequest{
				Name: "New Library",
				Directories: []*libraryv1.Directory{
					{Id: "1", Name: "Dir 1", Path: "/path1"},
				},
			},
			mockError:     nil,
			expectedError: false,
		},
		{
			name: "Error creating library",
			request: &libraryv1.CreateLibraryRequest{
				Name: "Failed Library",
			},
			mockError:     errors.New("database error"),
			expectedError: true,
			expectedCode:  codes.Internal,
		},
	}

	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			mockStore.On("CreateLibrary", mock.AnythingOfType("*libraryv1.Library")).Return(tc.mockError).Once()

			req := tc.request
			ctx := context.Background()
			resp, err := handler.CreateLibrary(ctx, req)

			if tc.expectedError {
				assert.Error(t, err)
				assert.Nil(t, resp)
				st, ok := status.FromError(err)
				assert.True(t, ok, "Expected gRPC status error, got %T", err)
				if ok {
					assert.Equal(t, tc.expectedCode, st.Code())
				}
			} else {
				assert.NoError(t, err)
				assert.NotNil(t, resp)
			}

			mockStore.AssertExpectations(t)
		})
	}
}

func TestAddDirectoryToLibrary(t *testing.T) {
	mockStore := new(MockLibraryStore)
	handler := NewLibraryHandler(mockStore)

	testCases := []struct {
		name          string
		libraryID     string
		directory     *libraryv1.Directory
		mockError     error
		expectedError bool
	}{
		{
			name:      "Successful addition",
			libraryID: "123",
			directory: &libraryv1.Directory{
				Id:   "dir1",
				Name: "New Directory",
				Path: "/new/path",
			},
			mockError:     nil,
			expectedError: false,
		},
		{
			name:      "Error adding directory",
			libraryID: "456",
			directory: &libraryv1.Directory{
				Id:   "dir2",
				Name: "Failed Directory",
				Path: "/failed/path",
			},
			mockError:     errors.New("database error"),
			expectedError: true,
		},
	}

	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			mockStore.On("AddDirectory", tc.libraryID, tc.directory).Return(tc.mockError)

			req := &libraryv1.AddDirectoryToLibraryRequest{
				LibraryId: tc.libraryID,
				Directory: tc.directory,
			}
			ctx := context.Background()
			resp, err := handler.AddDirectoryToLibrary(ctx, req)

			if tc.expectedError {
				assert.Error(t, err)
				assert.Nil(t, resp)
			} else {
				assert.NoError(t, err)
				assert.NotNil(t, resp)
			}

			mockStore.AssertExpectations(t)
		})
	}
}
