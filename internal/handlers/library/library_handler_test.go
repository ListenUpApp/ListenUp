package library

import (
	authv1 "buf.build/gen/go/listenup/listenup/protocolbuffers/go/listenup/auth/v1"
	folderv1 "buf.build/gen/go/listenup/listenup/protocolbuffers/go/listenup/folder/v1"
	libraryv1 "buf.build/gen/go/listenup/listenup/protocolbuffers/go/listenup/library/v1"
	userv1 "buf.build/gen/go/listenup/listenup/protocolbuffers/go/listenup/user/v1"
	"context"
	"errors"
	"github.com/ListenUpApp/ListenUp/internal/middleware"
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
	args := m.Called(ids)
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

type MockUserStore struct {
	mock.Mock
}

func (m *MockUserStore) GetUserById(ctx context.Context, id string) (*authv1.AuthUser, error) {
	args := m.Called(ctx, id)
	return args.Get(0).(*authv1.AuthUser), args.Error(1)
}

func (m *MockUserStore) UpdateUser(ctx context.Context, user *authv1.AuthUser) error {
	args := m.Called(ctx, user)
	return args.Error(0)
}

func (m *MockUserStore) CreateUser(ctx context.Context, user *authv1.AuthUser) error {
	args := m.Called(ctx, user)
	return args.Error(0)
}

func (m *MockUserStore) GetUserByEmail(ctx context.Context, email string) (*authv1.AuthUser, error) {
	args := m.Called(ctx, email)
	return args.Get(0).(*authv1.AuthUser), args.Error(1)
}

func TestGetLibrary(t *testing.T) {
	mockLibraryStore := new(MockLibraryStore)
	mockUserStore := new(MockUserStore)
	handler := NewLibraryHandler(mockLibraryStore, mockUserStore)

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
			mockLibraryStore.On("GetLibraryByID", tc.libraryID).Return(tc.mockLibrary, tc.mockError)
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

			mockLibraryStore.AssertExpectations(t)
		})
	}
}

func TestListLibraries(t *testing.T) {
	mockLibraryStore := new(MockLibraryStore)
	mockUserStore := new(MockUserStore)
	handler := NewLibraryHandler(mockLibraryStore, mockUserStore)

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
			mockLibraryStore.On("GetAllLibraries").Return(tc.mockLibraries, tc.mockError).Once()
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

			mockLibraryStore.AssertExpectations(t)
		})
	}
}

func TestCreateLibrary(t *testing.T) {
	mockLibraryStore := new(MockLibraryStore)
	mockUserStore := new(MockUserStore)
	handler := NewLibraryHandler(mockLibraryStore, mockUserStore)

	testCases := []struct {
		name          string
		request       *libraryv1.CreateLibraryRequest
		mockUser      *authv1.AuthUser
		createError   error
		expectedError bool
		expectedCode  codes.Code
	}{
		{
			name: "Successful creation",
			request: &libraryv1.CreateLibraryRequest{
				Name: "New Library",
				Folders: []*folderv1.Folder{
					{Name: "Dir 1", Path: "/path1"},
				},
			},
			mockUser: &authv1.AuthUser{
				User: &userv1.User{
					Id:               "user1",
					CurrentLibraryId: "",
					LibraryIds:       []string{},
				},
			},
			createError:   nil,
			expectedError: false,
		},
		{
			name: "Error creating library",
			request: &libraryv1.CreateLibraryRequest{
				Name: "Failed Library",
			},
			mockUser:      nil, // We don't expect GetUserById to be called in this case
			createError:   errors.New("database error"),
			expectedError: true,
			expectedCode:  codes.Internal,
		},
	}

	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			// Set up CreateLibrary expectation
			mockLibraryStore.On("CreateLibrary", mock.AnythingOfType("*libraryv1.Library")).Return(tc.createError).Once()

			if tc.createError == nil {
				// Only expect GetUserById and UpdateUser to be called if library creation is successful
				mockUserStore.On("GetUserById", mock.Anything, "user1").Return(tc.mockUser, nil).Once()
				mockUserStore.On("UpdateUser", mock.Anything, mock.AnythingOfType("*authv1.AuthUser")).Return(nil).Once()
			}

			ctx := context.WithValue(context.Background(), middleware.ClaimsKey, &middleware.Claims{UserID: "user1"})
			resp, err := handler.CreateLibrary(ctx, tc.request)

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
				assert.NotEmpty(t, resp.Library.Id)
				assert.Equal(t, tc.request.Name, resp.Library.Name)
				assert.Len(t, resp.Library.Directories, len(tc.request.Folders))
			}

			mockLibraryStore.AssertExpectations(t)
			mockUserStore.AssertExpectations(t)
		})
	}
}

func TestAddDirectoryToLibrary(t *testing.T) {
	mockLibraryStore := new(MockLibraryStore)
	mockUserStore := new(MockUserStore)
	handler := NewLibraryHandler(mockLibraryStore, mockUserStore)

	testCases := []struct {
		name          string
		libraryID     string
		directory     *folderv1.Folder
		mockError     error
		expectedError bool
	}{
		{
			name:      "Successful addition",
			libraryID: "123",
			directory: &folderv1.Folder{
				Name: "New Directory",
				Path: "/new/path",
			},
			mockError:     nil,
			expectedError: false,
		},
		{
			name:      "Error adding directory",
			libraryID: "456",
			directory: &folderv1.Folder{
				Name: "Failed Directory",
				Path: "/failed/path",
			},
			mockError:     errors.New("database error"),
			expectedError: true,
		},
	}

	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			mockLibraryStore.On("AddDirectory", tc.libraryID, mock.AnythingOfType("*libraryv1.Directory")).Return(tc.mockError)

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

			mockLibraryStore.AssertExpectations(t)
		})
	}
}

func TestGetLibrariesForUser(t *testing.T) {
	mockLibraryStore := new(MockLibraryStore)
	mockUserStore := new(MockUserStore)
	handler := NewLibraryHandler(mockLibraryStore, mockUserStore)

	testCases := []struct {
		name           string
		userID         string
		mockUser       *authv1.AuthUser
		mockLibraries  []*libraryv1.Library
		mockError      error
		expectedError  bool
		expectedCode   codes.Code
		expectedLength int
	}{
		{
			name:   "Successful retrieval",
			userID: "user1",
			mockUser: &authv1.AuthUser{
				User: &userv1.User{
					Id:         "user1",
					LibraryIds: []string{"lib1", "lib2"},
				},
			},
			mockLibraries: []*libraryv1.Library{
				{Id: "lib1", Name: "Library 1"},
				{Id: "lib2", Name: "Library 2"},
			},
			mockError:      nil,
			expectedError:  false,
			expectedLength: 2,
		},
		{
			name:   "User not found",
			userID: "user2",
			mockUser: &authv1.AuthUser{
				User: &userv1.User{
					Id: "user2",
				},
			},
			mockLibraries:  nil,
			mockError:      errors.New("user not found"),
			expectedError:  true,
			expectedCode:   codes.NotFound,
			expectedLength: 0,
		},
	}

	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			mockUserStore.On("GetUserById", mock.Anything, tc.userID).Return(tc.mockUser, tc.mockError).Once()
			if tc.mockError == nil {
				mockLibraryStore.On("GetLibrariesByIDs", tc.mockUser.User.LibraryIds).Return(tc.mockLibraries, nil).Once()
			}

			ctx := context.WithValue(context.Background(), middleware.ClaimsKey, &middleware.Claims{UserID: tc.userID})
			req := &libraryv1.GetLibrariesForUserRequest{}
			resp, err := handler.GetLibrariesForUser(ctx, req)

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

			mockUserStore.AssertExpectations(t)
			mockLibraryStore.AssertExpectations(t)
		})
	}
}
