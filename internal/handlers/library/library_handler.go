package library

import (
	libraryv1 "buf.build/gen/go/listenup/listenup/protocolbuffers/go/listenup/library/v1"
	"github.com/ListenUpApp/ListenUp/internal/store"
	gonanoid "github.com/matoous/go-nanoid/v2"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
	"google.golang.org/protobuf/types/known/timestamppb"
)

type LibraryHandler struct {
	libraryStore store.LibraryStore
}

func NewLibraryHandler(libraryStore store.LibraryStore) *LibraryHandler {
	return &LibraryHandler{
		libraryStore: libraryStore,
	}
}

func (h *LibraryHandler) GetLibrary(req *libraryv1.GetLibraryRequest) (*libraryv1.GetLibraryResponse, error) {
	// TODO Authorization

	library, err := h.libraryStore.GetLibraryByID(req.GetId())
	if err != nil {
		return nil, status.Errorf(codes.NotFound, "could not find library")
	}

	res := &libraryv1.GetLibraryResponse{
		Library: library,
	}
	return res, nil
}

func (h *LibraryHandler) ListLibraries(req *libraryv1.ListLibrariesRequest) (*libraryv1.ListLibrariesResponse, error) {
	libraries, err := h.libraryStore.GetAllLibraries()
	if err != nil {
		return nil, status.Errorf(codes.Internal, err.Error())
	}
	res := &libraryv1.ListLibrariesResponse{
		Libraries: libraries,
	}

	return res, nil
}

func (h *LibraryHandler) CreateLibrary(req *libraryv1.CreateLibraryRequest) (*libraryv1.CreateLibraryResponse, error) {
	id, err := gonanoid.Generate("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz", 8)
	if err != nil {
		return nil, status.Errorf(codes.Internal, err.Error())
	}
	newLibrary := libraryv1.Library{
		Id:          id,
		Name:        req.Name,
		TotalBooks:  0,
		Directories: req.Directories,
		CreatedAt:   timestamppb.Now(),
	}
	err = h.libraryStore.CreateLibrary(&newLibrary)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "could not create library")
	}
	res := &libraryv1.CreateLibraryResponse{}

	return res, nil
}

func (h *LibraryHandler) GetLibrariesForUser(req *libraryv1.GetLibrariesForUserRequest) (*libraryv1.GetLibrariesForUserResponse, error) {
	// TODO Authorization
	// TODO implement
	res := &libraryv1.GetLibrariesForUserResponse{}

	return res, nil
}
func (h *LibraryHandler) AddDirectoryToLibrary(req *libraryv1.AddDirectoryToLibraryRequest) (*libraryv1.AddDirectoryToLibraryResponse, error) {
	// TODO Authorization

	err := h.libraryStore.AddDirectory(req.GetLibraryId(), req.GetDirectory())
	if err != nil {
		return nil, status.Errorf(codes.Internal, "unable to add directory to library")
	}
	res := &libraryv1.AddDirectoryToLibraryResponse{}

	return res, nil
}
