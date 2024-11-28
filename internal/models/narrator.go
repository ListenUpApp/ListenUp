package models

import "time"

type Narrator struct {
	ID          string
	Name        string
	Description string
	imagePath   string
	createdAt   time.Time
	updatedAt   time.Time
}


type CreateNarratorRequest struct {
	Name        string
	Description string
}
