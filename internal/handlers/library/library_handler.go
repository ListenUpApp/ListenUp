package library

import (
	"buf.build/gen/go/listenup/listenup/connectrpc/go/listenup/library/v1/libraryv1connect"
	"github.com/ListenUpApp/ListenUp/internal/store"
)

type LibraryHandlers struct {
	userStore store.UserStore
	libraryv1connect.UnimplementedLibraryServiceHandler
}
