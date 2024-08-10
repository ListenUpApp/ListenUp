package utils

import (
	"github.com/golang-jwt/jwt/v5"
	"github.com/stretchr/testify/assert"
	"testing"
	"time"
)

func TestGenerateToken(t *testing.T) {
	testCases := []struct {
		name       string
		userID     string
		role       int32
		email      string
		expiration time.Duration
	}{
		{"Basic token", "user123", 0, "", time.Hour},
		{"Token with role", "user456", 1, "", time.Hour},
		{"Token with email", "user789", 0, "test@example.com", time.Hour},
		{"Full token", "user101", 2, "admin@example.com", 24 * time.Hour},
	}

	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			token, err := GenerateToken(tc.userID, tc.role, tc.email, tc.expiration)

			assert.NoError(t, err)
			assert.NotEmpty(t, token)

			claims, err := ParseToken(token)
			assert.NoError(t, err)

			assert.Equal(t, tc.userID, claims["user_id"])
			if tc.role != 0 {
				assert.Equal(t, float64(tc.role), claims["role"])
			} else {
				_, exists := claims["role"]
				assert.False(t, exists)
			}
			if tc.email != "" {
				assert.Equal(t, tc.email, claims["email"])
			} else {
				_, exists := claims["email"]
				assert.False(t, exists)
			}

			exp, ok := claims["exp"].(float64)
			assert.True(t, ok)
			assert.InDelta(t, time.Now().Add(tc.expiration).Unix(), int64(exp), 1)
		})
	}
}

func TestParseToken(t *testing.T) {
	testCases := []struct {
		name        string
		tokenString string
		valid       bool
	}{
		{"Valid token", "", true},
		{"Invalid token", "invalid.token.string", false},
		{"Expired token", "", false},
	}

	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			if tc.name == "Valid token" {
				var err error
				tc.tokenString, err = GenerateToken("testuser", 1, "test@example.com", time.Hour)
				assert.NoError(t, err)
			} else if tc.name == "Expired token" {
				var err error
				tc.tokenString, err = GenerateToken("testuser", 1, "test@example.com", -time.Hour)
				assert.NoError(t, err)
			}

			claims, err := ParseToken(tc.tokenString)

			if tc.valid {
				assert.NoError(t, err)
				assert.NotNil(t, claims)
				assert.Equal(t, "testuser", claims["user_id"])
				assert.Equal(t, float64(1), claims["role"])
				assert.Equal(t, "test@example.com", claims["email"])
			} else {
				assert.Error(t, err)
				assert.Nil(t, claims)
			}
		})
	}
}

func TestTokenExpiration(t *testing.T) {
	token, err := GenerateToken("user123", 0, "", time.Second)
	assert.NoError(t, err)

	// Wait for the token to expire
	time.Sleep(2 * time.Second)

	claims, err := ParseToken(token)
	assert.Error(t, err)
	assert.Nil(t, claims)
	assert.Contains(t, err.Error(), "token is expired")
}

func TestInvalidSigningMethod(t *testing.T) {
	// Create a token with a different signing method
	token := jwt.NewWithClaims(jwt.SigningMethodNone, jwt.MapClaims{
		"user_id": "user123",
	})
	tokenString, err := token.SignedString(jwt.UnsafeAllowNoneSignatureType)
	assert.NoError(t, err)

	claims, err := ParseToken(tokenString)
	assert.Error(t, err)
	assert.Nil(t, claims)
	assert.Contains(t, err.Error(), "unexpected signing method")
}
