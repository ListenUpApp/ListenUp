package library

import (
	libraryv1 "buf.build/gen/go/listenup/listenup/protocolbuffers/go/listenup/library/v1"
	"context"
	"github.com/ListenUpApp/ListenUp/internal/logger"
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

func (h *LibraryHandler) GetLibrary(ctx context.Context, req *libraryv1.GetLibraryRequest) (*libraryv1.GetLibraryResponse, error) {
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

func (h *LibraryHandler) ListLibraries(ctx context.Context, req *libraryv1.ListLibrariesRequest) (*libraryv1.ListLibrariesResponse, error) {
	libraries, err := h.libraryStore.GetAllLibraries()
	if err != nil {
		return nil, status.Errorf(codes.Internal, err.Error())
	}
	res := &libraryv1.ListLibrariesResponse{
		Libraries: libraries,
	}

	return res, nil
}

func (h *LibraryHandler) CreateLibrary(ctx context.Context, req *libraryv1.CreateLibraryRequest) (*libraryv1.CreateLibraryResponse, error) {
	id, err := gonanoid.Generate("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz", 8)
	logger.Info("Create Library Called")
	if err != nil {
		logger.Error("Library Id generation failed")
		return nil, status.Errorf(codes.Internal, err.Error())
	}
	var directories []*libraryv1.Directory
	for _, v := range req.Folders {
		directoryId, err := gonanoid.Generate("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz", 8)

		if err != nil {
			logger.Error("Directory Id generation failed")
			return nil, status.Errorf(codes.Internal, err.Error())
		}
		newDirectory := libraryv1.Directory{
			Id:         directoryId,
			Name:       v.Name,
			Path:       v.Path,
			TotalBooks: 0,
		}
		directories = append(directories, &newDirectory)
	}
	newLibrary := libraryv1.Library{
		Id:          id,
		Name:        req.Name,
		TotalBooks:  0,
		Directories: directories,
		CreatedAt:   timestamppb.Now(),
	}
	err = h.libraryStore.CreateLibrary(&newLibrary)
	//TODO add the newly created library to the Users current library once we get Auth in place.
	if err != nil {
		logger.Error("Error saving to the data base ", "Error", err)
		return nil, status.Errorf(codes.Internal, "could not create library")
	}
	res := &libraryv1.CreateLibraryResponse{
		Library: &newLibrary,
	}
	logger.Info("library creation finished successfully")
	return res, nil
}

func (h *LibraryHandler) GetLibrariesForUser(ctx context.Context, req *libraryv1.GetLibrariesForUserRequest) (*libraryv1.GetLibrariesForUserResponse, error) {
	// TODO Authorization
	// TODO implement
	res := &libraryv1.GetLibrariesForUserResponse{}

	return res, nil
}
func (h *LibraryHandler) AddDirectoryToLibrary(ctx context.Context, req *libraryv1.AddDirectoryToLibraryRequest) (*libraryv1.AddDirectoryToLibraryResponse, error) {
	// TODO Authorization

	folder := req.GetDirectory()
	id, err := gonanoid.Generate("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz", 8)
	if err != nil {
		logger.Error("Directory Id generation failed")
		return nil, status.Errorf(codes.Internal, err.Error())
	}
	newDirectory := libraryv1.Directory{
		Id:         id,
		Name:       folder.Name,
		Path:       folder.Path,
		TotalBooks: 0,
	}
	err = h.libraryStore.AddDirectory(req.GetLibraryId(), &newDirectory)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "unable to add directory to library")
	}
	res := &libraryv1.AddDirectoryToLibraryResponse{}

	return res, nil
}
