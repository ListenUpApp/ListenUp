package library

import (
	libraryv1 "buf.build/gen/go/listenup/listenup/protocolbuffers/go/listenup/library/v1"
	"context"
	"github.com/ListenUpApp/ListenUp/internal/logger"
	"github.com/ListenUpApp/ListenUp/internal/middleware"
	"github.com/ListenUpApp/ListenUp/internal/store"
	gonanoid "github.com/matoous/go-nanoid/v2"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
	"google.golang.org/protobuf/types/known/timestamppb"
)

type LibraryHandler struct {
	libraryStore store.LibraryStore
	userStore    store.UserStore
}

func NewLibraryHandler(libraryStore store.LibraryStore, userStore store.UserStore) *LibraryHandler {
	return &LibraryHandler{
		libraryStore: libraryStore,
		userStore:    userStore,
	}
}

func (h *LibraryHandler) GetLibrary(ctx context.Context, req *libraryv1.GetLibraryRequest) (*libraryv1.GetLibraryResponse, error) {
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
	if err != nil {
		return nil, status.Errorf(codes.Internal, "error creating library")
	}
	//TODO add the newly created library to the Users current library once we get Auth in place.
	claims, err := middleware.GetClaimsFromContext(ctx)

	user, err := h.userStore.GetUserById(ctx, claims.UserID)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "could not retrive user from token")
	}
	user.User.CurrentLibraryId = newLibrary.Id
	user.User.LibraryIds = append(user.User.LibraryIds, newLibrary.Id)

	err = h.userStore.UpdateUser(ctx, user)
	if err != nil {
		return nil, status.Errorf(codes.Internal, "failed to update user")
	}

	if err != nil {
		logger.Error("Error saving library to the database ", "Error", err)
		return nil, status.Errorf(codes.Internal, "could not create library")
	}
	res := &libraryv1.CreateLibraryResponse{
		Library: &newLibrary,
	}
	logger.Info("library creation finished successfully")
	return res, nil
}

func (h *LibraryHandler) GetLibrariesForUser(ctx context.Context, req *libraryv1.GetLibrariesForUserRequest) (*libraryv1.GetLibrariesForUserResponse, error) {
	claims, err := middleware.GetClaimsFromContext(ctx)
	if err != nil {
		return nil, status.Errorf(codes.Unauthenticated, "unauthenticated")
	}
	user, err := h.userStore.GetUserById(ctx, claims.UserID)
	if err != nil {
		return nil, status.Errorf(codes.NotFound, "could not find a user that matches this userId")
	}

	libraries, err := h.libraryStore.GetLibrariesByIDs(user.User.LibraryIds)
	if err != nil {
		return nil, status.Errorf(codes.NotFound, "could not find a library %v", err)
	}

	res := &libraryv1.GetLibrariesForUserResponse{
		Libraries: libraries,
	}

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
