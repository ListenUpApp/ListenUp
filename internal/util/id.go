package util

import (
	gonanoid "github.com/matoous/go-nanoid/v2"
)

// Configuration for our URL-safe NanoID
const (
	IDLength = 8
	// URL-safe alphabet excluding ambiguous characters
	Alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz-_"
)

// NewID generates a new URL-safe NanoID
func NewID() string {
	// Using Must variant since the alphabet and length are validated at compile time
	return gonanoid.MustGenerate(Alphabet, IDLength)
}

// Generate creates a new ID with custom alphabet and length
func Generate(alphabet string, length int) (string, error) {
	return gonanoid.Generate(alphabet, length)
}
