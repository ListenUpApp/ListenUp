package models

import (
	"github.com/ListenUpApp/ListenUp/pkg/taggart"
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

type ListAudiobook struct {
	ID     string
	Title  string
	Cover  Cover
	Author ListAuthor
}

type BookList struct {
	Books      []ListAudiobook
	Pagination Pagination
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
	CoverData     *taggart.Picture
	Cover         CreateCoverRequest
}
