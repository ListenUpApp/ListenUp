package models

type Library struct {
	ID      string   `json:"id"`
	Name    string   `json:"name"`
	Folders []Folder `json:"folders"`
}



type CreateLibraryRequest struct {
	Name    string               `json:"name"`
	Folders []CreateFoldeRequest `json:"folders"`
}

type CreateFoldeRequest struct {
	Name string `json:"id"`
	Path string `json:"path"`
}
