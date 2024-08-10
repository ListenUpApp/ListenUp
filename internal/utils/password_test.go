package utils

import (
	"github.com/stretchr/testify/assert"
	"strings"
	"testing"
)

func TestHashPassword(t *testing.T) {
	testCases := []struct {
		name        string
		password    string
		shouldError bool
	}{
		{"Simple password", "password123", false},
		{"Complex password", "C0mpl3x!P@ssw0rd", false},
		{"Empty password", "", false},
		{"Max length password", strings.Repeat("a", 72), false},
		{"Exceeds max length", strings.Repeat("a", 73), true},
	}

	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			hashedPassword, err := HashPassword(tc.password)

			if tc.shouldError {
				assert.Error(t, err)
				assert.Contains(t, err.Error(), "bcrypt: password length exceeds 72 bytes")
				assert.Empty(t, hashedPassword)
			} else {
				assert.NoError(t, err)
				assert.NotEmpty(t, hashedPassword)
				assert.NotEqual(t, tc.password, hashedPassword)

				// Check that the same password hashed twice produces different hashes
				hashedPassword2, _ := HashPassword(tc.password)
				assert.NotEqual(t, hashedPassword, hashedPassword2)
			}
		})
	}
}

func TestCheckPasswordHash(t *testing.T) {
	testCases := []struct {
		name        string
		password    string
		matchShould bool
	}{
		{"Correct password", "password123", true},
		{"Incorrect password", "wrongpassword", false},
		{"Empty password", "", false},
	}

	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			hashedPassword, err := HashPassword("password123") // Always hash "password123"
			assert.NoError(t, err)

			result := CheckPasswordHash(tc.password, hashedPassword)
			assert.Equal(t, tc.matchShould, result)

			if tc.matchShould {
				// Also check that a different password doesn't match
				assert.False(t, CheckPasswordHash("wrongpassword", hashedPassword))
			}
		})
	}
}

func TestCheckPasswordHashWithInvalidHash(t *testing.T) {
	password := "password123"
	invalidHash := "thisisnotavalidbcrypthash"

	result := CheckPasswordHash(password, invalidHash)
	assert.False(t, result)
}
