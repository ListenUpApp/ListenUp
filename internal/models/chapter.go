package models

type Chapter struct {
	Index int
	Title string
	Start float64
	End   float64
}

type CreateChapterRequest struct {
	Title string
	Start float64
	End   float64
}
