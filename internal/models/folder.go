package models

import "time"

type Folder struct {
	ID            string    `json:"id"`
	Name          string    `json:"id"`
	Path          string    `json:"path"`
	LastScannedAt time.Time `json:lastScannedAt`
}

type GetFoldeRequest struct {
	Path  string `json:"path"`
	Depth int    `json:"depth"`
}

type GetFolderResponse struct {
	Name       string              `json:"name"`
	Path       string              `json:"path"`
	Depth      int                 `json:"depth"`
	SubFolders []GetFolderResponse `json:"subFolders"`
}

type CreateFolderRequest struct {
	Name string `json:"name" form:"name"`
	Path string `json:"path" form:"path"`
}