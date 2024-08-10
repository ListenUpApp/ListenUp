package validator

import (
	"google.golang.org/genproto/googleapis/rpc/errdetails"
	"testing"

	authv1 "buf.build/gen/go/listenup/listenup/protocolbuffers/go/listenup/auth/v1"
	"connectrpc.com/connect"
	"github.com/stretchr/testify/assert"
)

func TestValidateRegisterRequest(t *testing.T) {
	tests := []struct {
		name          string
		input         *connect.Request[authv1.RegisterRequest]
		expectedError bool
		errorFields   []string
	}{
		{
			name: "Valid request",
			input: &connect.Request[authv1.RegisterRequest]{
				Msg: &authv1.RegisterRequest{
					Email:    "test@example.com",
					Name:     "John Doe",
					Password: "password123",
				},
			},
			expectedError: false,
		},
		{
			name: "Invalid email",
			input: &connect.Request[authv1.RegisterRequest]{
				Msg: &authv1.RegisterRequest{
					Email:    "invalid-email",
					Name:     "John Doe",
					Password: "password123",
				},
			},
			expectedError: true,
			errorFields:   []string{"Email"},
		},
		{
			name: "Missing name",
			input: &connect.Request[authv1.RegisterRequest]{
				Msg: &authv1.RegisterRequest{
					Email:    "test@example.com",
					Password: "password123",
				},
			},
			expectedError: true,
			errorFields:   []string{"Name"},
		},
		{
			name: "Password too short",
			input: &connect.Request[authv1.RegisterRequest]{
				Msg: &authv1.RegisterRequest{
					Email:    "test@example.com",
					Name:     "John Doe",
					Password: "short",
				},
			},
			expectedError: true,
			errorFields:   []string{"Password"},
		},
		{
			name: "Multiple errors",
			input: &connect.Request[authv1.RegisterRequest]{
				Msg: &authv1.RegisterRequest{
					Email:    "invalid-email",
					Password: "short",
				},
			},
			expectedError: true,
			errorFields:   []string{"Email", "Name", "Password"},
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			violations := ValidateRegisterRequest(tt.input)

			if tt.expectedError {
				assert.NotEmpty(t, violations, "Expected validation errors, but got none")
				for _, field := range tt.errorFields {
					assert.Contains(t, getViolationFields(violations), field, "Expected error for field %s", field)
				}
			} else {
				assert.Empty(t, violations, "Expected no validation errors, but got: %v", violations)
			}
		})
	}
}

func TestValidateLoginRequest(t *testing.T) {
	tests := []struct {
		name          string
		input         *connect.Request[authv1.LoginRequest]
		expectedError bool
		errorFields   []string
	}{
		{
			name: "Valid request",
			input: &connect.Request[authv1.LoginRequest]{
				Msg: &authv1.LoginRequest{
					Email:    "test@example.com",
					Password: "password123",
				},
			},
			expectedError: false,
		},
		{
			name: "Invalid email",
			input: &connect.Request[authv1.LoginRequest]{
				Msg: &authv1.LoginRequest{
					Email:    "invalid-email",
					Password: "password123",
				},
			},
			expectedError: true,
			errorFields:   []string{"Email"},
		},
		{
			name: "Password too short",
			input: &connect.Request[authv1.LoginRequest]{
				Msg: &authv1.LoginRequest{
					Email:    "test@example.com",
					Password: "short",
				},
			},
			expectedError: true,
			errorFields:   []string{"Password"},
		},
		{
			name: "Multiple errors",
			input: &connect.Request[authv1.LoginRequest]{
				Msg: &authv1.LoginRequest{
					Email:    "invalid-email",
					Password: "short",
				},
			},
			expectedError: true,
			errorFields:   []string{"Email", "Password"},
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			violations := ValidateLoginRequest(tt.input)

			if tt.expectedError {
				assert.NotEmpty(t, violations, "Expected validation errors, but got none")
				for _, field := range tt.errorFields {
					assert.Contains(t, getViolationFields(violations), field, "Expected error for field %s", field)
				}
			} else {
				assert.Empty(t, violations, "Expected no validation errors, but got: %v", violations)
			}
		})
	}
}

func getViolationFields(violations []*errdetails.BadRequest_FieldViolation) []string {
	fields := make([]string, len(violations))
	for i, v := range violations {
		fields[i] = v.Field
	}
	return fields
}
