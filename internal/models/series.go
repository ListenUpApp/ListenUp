package models

type CreateSeriesRequest struct {
	Name     string
	Sequence float64
}

type Series struct {
	ID          string
	Name        string
	Description string
	Sequence    float64
}
