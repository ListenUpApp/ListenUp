package models

import "time"

type Author struct {
	ID          string
	Name        string
	Description string
	imagePath   string
	createdAt   time.Time
	updatedAt   time.Time
}

type ListAuthor struct {
	ID   string
	Name string
}

type CreateAuthorRequest struct {
	Name        string
	Description string
}
