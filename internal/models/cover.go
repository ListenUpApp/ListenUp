package models

import "time"

type Cover struct {
	Path      string
	Format    string
	Size      int64
	UpdatedAt time.Time
}

type CreateCoverRequest struct {
	Path   string
	Format string
	Size   int64
}
