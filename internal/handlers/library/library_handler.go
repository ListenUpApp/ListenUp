package library

import (
	"buf.build/gen/go/listenup/listenup/connectrpc/go/listenup/library/v1/libraryv1connect"
	libraryv1 "buf.build/gen/go/listenup/listenup/protocolbuffers/go/listenup/library/v1"
	"connectrpc.com/connect"
	"errors"
	"github.com/ListenUpApp/ListenUp/internal/store"
	gonanoid "github.com/matoous/go-nanoid/v2"
	"google.golang.org/protobuf/types/known/timestamppb"
)

type LibraryHandler struct {
	libraryStore store.LibraryStore
	libraryv1connect.UnimplementedLibraryServiceHandler
}

func NewLibraryHandler(libraryStore store.LibraryStore) *LibraryHandler {
	return &LibraryHandler{
		libraryStore: libraryStore,
	}
}

func (h *LibraryHandler) GetLibrary(req connect.Request[libraryv1.GetLibraryRequest]) (*connect.Response[libraryv1.GetLibraryResponse], error) {
	// TODO Authorization

	library, err := h.libraryStore.GetLibraryByID(req.Msg.GetId())
	if err != nil {
		return nil, connect.NewError(connect.CodeNotFound, errors.New("could not retrieve server"))
	}

	res := connect.NewResponse(&libraryv1.GetLibraryResponse{
		Library: library,
	})
	return res, nil
}

func (h *LibraryHandler) ListLibraries(req connect.Request[libraryv1.ListLibrariesRequest]) (*connect.Response[libraryv1.ListLibrariesResponse], error) {
	libraries, err := h.libraryStore.GetAllLibraries()
	if err != nil {
		return nil, connect.NewError(connect.CodeInternal, err)
	}
	res := connect.NewResponse(&libraryv1.ListLibrariesResponse{
		Libraries: libraries,
	})

	return res, nil
}

func (h *LibraryHandler) CreateLibrary(req connect.Request[libraryv1.CreateLibraryRequest]) (*connect.Response[libraryv1.CreateLibraryResponse], error) {
	id, err := gonanoid.Generate("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz", 8)
	if err != nil {
		return nil, connect.NewError(connect.CodeInternal, err)
	}
	newLibrary := libraryv1.Library{
		Id:          id,
		Name:        req.Msg.Name,
		TotalBooks:  0,
		Directories: req.Msg.Directories,
		CreatedAt:   timestamppb.Now(),
	}
	err = h.libraryStore.CreateLibrary(&newLibrary)
	if err != nil {
		return nil, connect.NewError(connect.CodeInternal, err)
	}
	res := connect.NewResponse(&libraryv1.CreateLibraryResponse{})

	return res, nil
}

func (h *LibraryHandler) GetLibrariesForUser(req connect.Request[libraryv1.GetLibrariesForUserRequest]) (*connect.Response[libraryv1.GetLibrariesForUserResponse], error) {
	// TODO Authorization
	// TODO implement
	res := connect.NewResponse(&libraryv1.GetLibrariesForUserResponse{})

	return res, nil
}
func (h *LibraryHandler) AddDirectoryToLibrary(req connect.Request[libraryv1.AddDirectoryToLibraryRequest]) (*connect.Response[libraryv1.AddDirectoryToLibraryResponse], error) {
	// TODO Authorization

	err := h.libraryStore.AddDirectory(req.Msg.GetLibraryId(), req.Msg.GetDirectory())
	if err != nil {
		return nil, connect.NewError(connect.CodeInternal, errors.New("could not add directory to library"))
	}
	res := connect.NewResponse(&libraryv1.AddDirectoryToLibraryResponse{})

	return res, nil
}
