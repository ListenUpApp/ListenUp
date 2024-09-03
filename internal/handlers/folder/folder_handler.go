package folder

import (
	folderv1 "buf.build/gen/go/listenup/listenup/protocolbuffers/go/listenup/folder/v1"
	"context"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
	"os"
	"path/filepath"
	"strings"
)

type FolderHandler struct {
}

func NewFoldersHandler() *FolderHandler {
	return &FolderHandler{}
}

func (h *FolderHandler) GetFolder(ctx context.Context, req *folderv1.GetFolderRequest) (*folderv1.GetFolderResponse, error) {
	// TODO auth checking

	path := req.GetPath()
	var folders []*folderv1.Folder

	// Check if the path is empty to return the root drives
	if path == "" {
		drives, err := getUnixDrives()
		if err != nil {
			return nil, status.Errorf(codes.Internal, "failed to get drives: %v", err)
		}
		for _, drive := range drives {
			folders = append(folders, &folderv1.Folder{
				Path:  drive,
				Name:  drive,
				Level: 0,
			})
		}
	} else {
		dirEntries, err := os.ReadDir(path)
		if err != nil {
			return nil, status.Errorf(codes.Internal, "failed to read directory: %v", err)
		}

		for _, entry := range dirEntries {
			if entry.IsDir() {
				depth := len(strings.Split(filepath.Join(path, entry.Name()), string(os.PathSeparator)))
				folders = append(folders, &folderv1.Folder{
					Path:  filepath.Join(path, entry.Name()),
					Name:  entry.Name(),
					Level: int32(depth),
				})
			}
		}
	}

	res := &folderv1.GetFolderResponse{
		Folders: folders,
	}

	return res, nil
}

// getUnixDrives returns a list of all drives on Unix-like operating systems.
func getUnixDrives() ([]string, error) {
	var drives []string
	seenMounts := make(map[string]bool)

	// Read the contents of /proc/mounts
	data, err := os.ReadFile("/proc/mounts")
	if err != nil {
		return nil, err
	}

	// Split the file contents into lines
	lines := strings.Split(string(data), "\n")

	for _, line := range lines {
		fields := strings.Fields(line) // Split line into fields
		if len(fields) < 2 {           // Ensure there are enough fields
			continue
		}
		drivePath := fields[1] // The second field is the mount point

		// Skip pseudo filesystems (like tmpfs, sysfs, etc.)
		if strings.HasPrefix(drivePath, "/sys") || strings.HasPrefix(drivePath, "/proc") || strings.HasPrefix(drivePath, "/run") || strings.HasPrefix(drivePath, "/dev") {
			continue
		}
		// Dedupe repeated mounts
		if _, exists := seenMounts[drivePath]; exists {
			continue
		}
		seenMounts[drivePath] = true

		drives = append(drives, drivePath)
	}

	return drives, nil
}
