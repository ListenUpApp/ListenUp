package library

import (
	libraryv1 "buf.build/gen/go/listenup/listenup/protocolbuffers/go/listenup/library/v1"
	"connectrpc.com/connect"
	"errors"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"
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

			req := connect.NewRequest(&libraryv1.GetLibraryRequest{Id: tc.libraryID})
			resp, err := handler.GetLibrary(*req)

			if tc.expectedError {
				assert.Error(t, err)
				assert.Nil(t, resp)
			} else {
				assert.NoError(t, err)
				assert.NotNil(t, resp)
				assert.Equal(t, tc.mockLibrary, resp.Msg.Library)
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
		expectedCode   connect.Code
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
			expectedCode:   connect.CodeInternal,
			expectedLength: 0,
		},
	}

	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			mockStore.On("GetAllLibraries").Return(tc.mockLibraries, tc.mockError).Once()

			req := connect.NewRequest(&libraryv1.ListLibrariesRequest{})
			resp, err := handler.ListLibraries(*req)

			if tc.expectedError {
				assert.Error(t, err)
				assert.Nil(t, resp)
				connectErr, ok := err.(*connect.Error)
				assert.True(t, ok, "Expected connect.Error, got %T", err)
				if ok {
					assert.Equal(t, tc.expectedCode, connectErr.Code())
				}
			} else {
				assert.NoError(t, err)
				assert.NotNil(t, resp)
				assert.Len(t, resp.Msg.Libraries, tc.expectedLength)
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
		expectedCode  connect.Code
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
			expectedCode:  connect.CodeInternal,
		},
	}

	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			mockStore.On("CreateLibrary", mock.AnythingOfType("*libraryv1.Library")).Return(tc.mockError).Once()

			req := connect.NewRequest(tc.request)
			resp, err := handler.CreateLibrary(*req)

			if tc.expectedError {
				assert.Error(t, err)
				assert.Nil(t, resp)
				connectErr, ok := err.(*connect.Error)
				assert.True(t, ok, "Expected connect.Error, got %T", err)
				if ok {
					assert.Equal(t, tc.expectedCode, connectErr.Code())
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

			req := connect.NewRequest(&libraryv1.AddDirectoryToLibraryRequest{
				LibraryId: tc.libraryID,
				Directory: tc.directory,
			})
			resp, err := handler.AddDirectoryToLibrary(*req)

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
