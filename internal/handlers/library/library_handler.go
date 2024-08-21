package library

import (
	"buf.build/gen/go/listenup/listenup/connectrpc/go/listenup/library/v1/libraryv1connect"
	libraryv1 "buf.build/gen/go/listenup/listenup/protocolbuffers/go/listenup/library/v1"
	"connectrpc.com/connect"
	"github.com/ListenUpApp/ListenUp/internal/store"
)

type LibraryHandler struct {
	userStore store.UserStore
	libraryv1connect.UnimplementedLibraryServiceHandler
}

func NewLibraryHandler(userStore store.UserStore) *LibraryHandler {
	return &LibraryHandler{
		userStore: userStore,
	}
}

func (h *LibraryHandler) GetLibrary(request connect.Request[libraryv1.GetLibraryRequest]) (*connect.Response[libraryv1.GetLibraryResponse], error) {
	res := connect.NewResponse(&libraryv1.GetLibraryResponse{})

	return res, nil
}
func (h *LibraryHandler) ListLibraries(request connect.Request[libraryv1.ListLibrariesRequest]) (*connect.Response[libraryv1.ListLibrariesResponse], error) {
	res := connect.NewResponse(&libraryv1.ListLibrariesResponse{})

	return res, nil
}
func (h *LibraryHandler) CreateLibrary(request connect.Request[libraryv1.CreateLibraryRequest]) (*connect.Response[libraryv1.CreateLibraryResponse], error) {
	res := connect.NewResponse(&libraryv1.CreateLibraryResponse{})

	return res, nil
}
func (h *LibraryHandler) GetLibrariesForUser(request connect.Request[libraryv1.GetLibrariesForUserRequest]) (*connect.Response[libraryv1.GetLibrariesForUserResponse], error) {
	res := connect.NewResponse(&libraryv1.GetLibrariesForUserResponse{})

	return res, nil
}
func (h *LibraryHandler) AddDirectoryToLibrary(request connect.Request[libraryv1.AddDirectoryToLibraryRequest]) (*connect.Response[libraryv1.AddDirectoryToLibraryResponse], error) {
	res := connect.NewResponse(&libraryv1.AddDirectoryToLibraryResponse{})

	return res, nil
}
