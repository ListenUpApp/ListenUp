package validator

import (
	"github.com/go-playground/validator/v10"
	"reflect"
	"strings"
)

var validate *validator.Validate

func init() {
	validate = validator.New()

	// Register function to get json tag names
	validate.RegisterTagNameFunc(func(fld reflect.StructField) string {
		name := strings.SplitN(fld.Tag.Get("json"), ",", 2)[0]
		if name == "-" {
			return ""
		}
		return name
	})
}

// ValidationError represents a validation error
type ValidationError struct {
	Field string `json:"field"`
	Tag   string `json:"tag"`
	Value string `json:"value"`
}

// Validate validates any struct and returns validation errors
func Validate(s interface{}) map[string]string {
	err := validate.Struct(s)
	if err == nil {
		return nil
	}

	errors := make(map[string]string)

	for _, err := range err.(validator.ValidationErrors) {
		switch err.Tag() {
		case "required":
			errors[err.Field()] = "This field is required"
		case "email":
			errors[err.Field()] = "Invalid email format"
		case "min":
			errors[err.Field()] = "Must be at least " + err.Param() + " characters long"
		case "max":
			errors[err.Field()] = "Must not exceed " + err.Param() + " characters"
		case "passwd":
			errors[err.Field()] = "Password must be at least 8 characters and include uppercase, lowercase, number, and special character"
		case "eqfield":
			errors[err.Field()] = "Passwords do not match"
		default:
			errors[err.Field()] = "Invalid value"
		}
	}

	return errors
}
