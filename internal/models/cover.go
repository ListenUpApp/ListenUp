package models

import "time"

type Cover struct {
	Path      string
	Format    string
	Size      int64
	UpdatedAt time.Time
	Versions  []CoverVersion
}

type CoverVersion struct {
	Path   string
	Format string
	Size   int64
	Suffix string // e.g. "thumbnail", "small", "medium", "large"
}

type CreateCoverRequest struct {
	Path     string
	Format   string
	Size     int64
	Versions []CreateCoverVersionRequest
}

type CreateCoverVersionRequest struct {
	Path   string
	Format string
	Size   int64
	Suffix string
}
type ProcessedImage struct {
	OriginalPath string
	Path         string // Add path for original version
	Format       string // Add format
	Size         int64  // Add size
	Versions     []CoverVersion
}
