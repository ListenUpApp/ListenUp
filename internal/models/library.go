package models

import "time"

type Library struct {
	ID      string   `json:"id"`
	Name    string   `json:"name"`
	Folders []Folder `json:"folders"`
}

type Folder struct {
	ID            string    `json:"id"`
	Name          string    `json:"id"`
	Path          string    `json:"path"`
	LastScannedAt time.Time `json:lastScannedAt`
}

type CreateLibraryRequest struct {
	Name    string               `json:"name"`
	Folders []CreateFoldeRequest `json:"folders"`
}

type CreateFoldeRequest struct {
	Name string `json:"id"`
	Path string `json:"path"`
}
