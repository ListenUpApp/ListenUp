package models

import (
	"time"
)

type Audiobook struct {
	ID            string
	Title         string
	Duration      int64
	Size          int64
	Subtitle      string
	Description   string
	Isbn          string
	Asin          string
	Language      string
	Explicit      bool
	Publisher     string
	Genres        []string
	PublishedDate time.Time
	Authors       []Author
	Narrators     []Narrator
	Chapters      []Chapter
	Cover         Cover
	CreatedAt     time.Time
	UpdatedAt     time.Time
}

type CreateAudiobookRequest struct {
	Title         string
	Duration      int64
	Size          int64
	Subtitle      string
	Description   string
	Isbn          string
	Asin          string
	Language      string
	Publisher     string
	PublishedDate time.Time
	Genres        []string
	Authors       []CreateAuthorRequest
	Narrators     []CreateNarratorRequest
	Chapter       []CreateChapterRequest
	Cover         CreateCoverRequest
}
