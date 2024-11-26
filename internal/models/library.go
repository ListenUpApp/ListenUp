package models

type Library struct {
	ID      string   `json:"id"`
	Name    string   `json:"name"`
	Folders []Folder `json:"folders"`
}

type CreateLibraryRequest struct {
	Name    string               `json:"name" form:"name"`
	Folders []CreateFolderRequest `json:"folders" form:"folders"`
}