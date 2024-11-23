package util

import (
	"crypto/rand"
	"encoding/base64"
	"errors"
	"fmt"
	"github.com/ListenUpApp/ListenUp/internal/config"
	"github.com/gin-gonic/gin"
	"github.com/golang-jwt/jwt/v5"
	"log/slog"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

var (
	// Cookie configuration
	cookieConfig  config.CookieConfig
	isInitialized bool

	// JWT key manager
	keyManager     *jwtKeyManager
	keyManagerOnce sync.Once

	// Errors
	ErrInvalidToken = errors.New("invalid token")
	ErrExpiredToken = errors.New("token has expired")
)

const (
	// Cookie settings
	TokenCookieName = "auth_token"
	cookieMaxAge    = 24 * time.Hour
	httpOnlyCookie  = true
	sameSiteMode    = http.SameSiteLaxMode

	// JWT key settings
	keyLength    = 32 // 256 bits
	defaultPath  = ".jwt/signing.key"
	filePermMode = 0600
	dirPermMode  = 0700
)

type jwtKeyManager struct {
	signingKey []byte
}

// InitializeAuth sets up both cookie and JWT systems
func InitializeAuth(cfg config.CookieConfig) error {
	// Initialize cookie config
	cookieConfig = cfg
	isInitialized = true

	// Initialize JWT key manager
	return initKeyManager()
}

// JWT Key Management
func initKeyManager() error {
	var initErr error
	keyManagerOnce.Do(func() {
		keyManager = &jwtKeyManager{}
		initErr = keyManager.initialize()
	})
	return initErr
}

func (km *jwtKeyManager) initialize() error {
	home, err := os.UserHomeDir()
	if err != nil {
		return fmt.Errorf("could not get home directory: %w", err)
	}

	keyPath := filepath.Join(home, defaultPath)

	key, err := km.loadExistingKey(keyPath)
	if err == nil {
		km.signingKey = key
		return nil
	}

	return km.generateNewKey(keyPath)
}

func (km *jwtKeyManager) loadExistingKey(keyPath string) ([]byte, error) {
	encodedKey, err := os.ReadFile(keyPath)
	if err != nil {
		return nil, err
	}
	return base64.StdEncoding.DecodeString(string(encodedKey))
}

func (km *jwtKeyManager) generateNewKey(keyPath string) error {
	key := make([]byte, keyLength)
	if _, err := rand.Read(key); err != nil {
		return fmt.Errorf("failed to generate random key: %w", err)
	}

	dir := filepath.Dir(keyPath)
	if err := os.MkdirAll(dir, dirPermMode); err != nil {
		return fmt.Errorf("failed to create key directory: %w", err)
	}

	encodedKey := base64.StdEncoding.EncodeToString(key)
	if err := os.WriteFile(keyPath, []byte(encodedKey), filePermMode); err != nil {
		return fmt.Errorf("failed to save key: %w", err)
	}

	km.signingKey = key
	return nil
}

func getJWTSecret() []byte {
	if keyManager == nil {
		panic("JWT system not initialized. Call InitializeAuth during application startup")
	}
	return keyManager.signingKey
}

// Token Management
func GenerateToken(userID string, role int32, email string, expiration time.Duration) (string, error) {
	claims := jwt.MapClaims{
		"user_id": userID,
		"exp":     time.Now().Add(expiration).Unix(),
	}

	if role != 0 {
		claims["role"] = role
	}
	if email != "" {
		claims["email"] = email
	}

	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	signedToken, err := token.SignedString(getJWTSecret())
	if err != nil {
		return "", fmt.Errorf("failed to sign token: %w", err)
	}
	return signedToken, nil
}

func ParseToken(tokenString string) (jwt.MapClaims, error) {
	token, err := jwt.Parse(tokenString, func(token *jwt.Token) (interface{}, error) {
		if _, ok := token.Method.(*jwt.SigningMethodHMAC); !ok {
			return nil, errors.New("unexpected signing method")
		}
		return getJWTSecret(), nil
	})

	if err != nil {
		if errors.Is(err, jwt.ErrTokenExpired) {
			return nil, ErrExpiredToken
		}
		return nil, fmt.Errorf("failed to parse token: %w", err)
	}

	if !token.Valid {
		return nil, ErrInvalidToken
	}

	claims, ok := token.Claims.(jwt.MapClaims)
	if !ok {
		return nil, ErrInvalidToken
	}

	return claims, nil
}

// Cookie Management
func SetAuthCookie(c *gin.Context, token string) {
	if !isInitialized {
		panic("Auth system not initialized")
	}

	c.SetCookie(
		TokenCookieName,
		token,
		int(cookieMaxAge.Seconds()),
		cookieConfig.CookiePath,
		cookieConfig.Domain,
		cookieConfig.Domain != "",
		httpOnlyCookie,
	)
	c.SetSameSite(sameSiteMode)
}

func ClearAuthCookie(c *gin.Context) {
	if !isInitialized {
		panic("Auth system not initialized")
	}

	c.SetCookie(
		TokenCookieName,
		"",
		-1,
		cookieConfig.CookiePath,
		cookieConfig.Domain,
		cookieConfig.Domain != "",
		httpOnlyCookie,
	)
}

func GetAuthCookie(c *gin.Context) (string, error) {
	return c.Cookie(TokenCookieName)
}

func WebAuth() gin.HandlerFunc {
	if !isInitialized {
		panic("Auth system not initialized")
	}

	return func(c *gin.Context) {
		tokenString, err := GetAuthCookie(c)
		if err != nil {
			slog.Info("no auth cookie found, redirecting to login",
				"path", c.Request.URL.Path)
			c.Header("HX-Redirect", "/auth/login")
			c.Header("Location", "/auth/login")
			c.Status(http.StatusTemporaryRedirect) // Add status code
			c.Abort()
			return
		}

		claims, err := ParseToken(tokenString)
		if err != nil {
			slog.Info("invalid token, redirecting to login",
				"path", c.Request.URL.Path,
				"error", err)
			ClearAuthCookie(c)
			c.Header("HX-Redirect", "/auth/login")
			c.Header("Location", "/auth/login")
			c.Status(http.StatusTemporaryRedirect) // Add status code
			c.Abort()
			return
		}

		c.Set("claims", claims)
		c.Next()
	}
}

func APIAuth() gin.HandlerFunc {
	if !isInitialized {
		panic("Auth system not initialized")
	}

	return func(c *gin.Context) {
		authHeader := c.GetHeader("Authorization")
		if !strings.HasPrefix(authHeader, "Bearer ") {
			c.JSON(http.StatusUnauthorized, gin.H{
				"error":   "unauthorized",
				"message": "Valid authentication token required",
			})
			c.Abort()
			return
		}

		token := strings.TrimPrefix(authHeader, "Bearer ")
		claims, err := ParseToken(token)
		if err != nil {
			var response gin.H
			if errors.Is(err, ErrExpiredToken) {
				response = gin.H{
					"error":   "token_expired",
					"message": "Token has expired",
				}
			} else {
				response = gin.H{
					"error":   "invalid_token",
					"message": "Invalid authentication token",
				}
			}
			c.JSON(http.StatusUnauthorized, response)
			c.Abort()
			return
		}

		c.Set("claims", claims)
		c.Next()
	}
}
