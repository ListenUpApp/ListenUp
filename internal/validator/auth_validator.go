package validator

import (
	authv1 "buf.build/gen/go/listenup/listenup/protocolbuffers/go/listenup/auth/v1"
	"connectrpc.com/connect"
	"errors"
	"fmt"
	"github.com/go-playground/validator/v10"
	"google.golang.org/genproto/googleapis/rpc/errdetails"
)

var validate = validator.New()

func validateRequest(v interface{}) []*errdetails.BadRequest_FieldViolation {
	err := validate.Struct(v)
	if err == nil {
		return nil
	}

	var violations []*errdetails.BadRequest_FieldViolation

	var validationErrors validator.ValidationErrors
	ok := errors.As(err, &validationErrors)
	if !ok {
		return []*errdetails.BadRequest_FieldViolation{
			{
				Field:       "unknown",
				Description: "An unexpected error occurred during validation",
			},
		}
	}

	for _, err := range validationErrors {
		violations = append(violations, &errdetails.BadRequest_FieldViolation{
			Field:       err.Field(),
			Description: fmt.Sprintf("Validation failed: %s", err.Error()),
		})
	}

	return violations
}

func ValidateRegisterRequest(req *connect.Request[authv1.RegisterRequest]) []*errdetails.BadRequest_FieldViolation {
	return validateRequest(struct {
		Email    string `validate:"required,email"`
		Name     string `validate:"required"`
		Password string `validate:"required,min=8"`
	}{
		Email:    req.Msg.GetEmail(),
		Name:     req.Msg.GetName(),
		Password: req.Msg.GetPassword(),
	})
}

func ValidateLoginRequest(req *connect.Request[authv1.LoginRequest]) []*errdetails.BadRequest_FieldViolation {
	return validateRequest(struct {
		Email    string `validate:"required,email"`
		Password string `validate:"required,min=8"`
	}{
		Email:    req.Msg.GetEmail(),
		Password: req.Msg.GetPassword(),
	})
}
