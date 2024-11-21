package models

import "time"

type User struct {
	ID             uint      `json:"id"`
	Email          string    `json:"email"`
	HashedPassword string    `json:"-"` // "-" prevents password from being included in JSON
	FirstName      string    `json:"first_name"`
	LastName       string    `json:"last_name"`
	CreatedAt      time.Time `json:"created_at"`
	UpdatedAt      time.Time `json:"updated_at"`
}

type RegisterRequest struct {
	FirstName       string `json:"firstName"`
	LastName        string `json:"lastName"`
	Email           string `json:"email"`
	Password        string `json:"password"`
	ConfirmPassword string `json:"confirmPassword"`
}

type CreateUser struct {
	Email          string `json:"email"`
	HashedPassword string `json:"hashed_password"`
	FirstName      string `json:"first_name"`
	LastName       string `json:"last_name"`
}
