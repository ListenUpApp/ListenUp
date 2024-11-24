package util

import (
	"crypto/rand"
	"encoding/base64"
	"errors"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"sync"
	"time"

	"github.com/ListenUpApp/ListenUp/internal/config"
	"github.com/gin-gonic/gin"
	"github.com/golang-jwt/jwt/v5"
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

// CustomClaims extends jwt.MapClaims to provide strongly typed fields
type CustomClaims struct {
	UserID string `json:"user_id"`
	Role   int32  `json:"role,omitempty"`
	Email  string `json:"email,omitempty"`
	jwt.MapClaims
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
	claims := CustomClaims{
		UserID: userID,
		Role:   role,
		Email:  email,
		MapClaims: jwt.MapClaims{
			"exp": time.Now().Add(expiration).Unix(),
		},
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

// GetUserIDFromClaims safely extracts the user ID from the context
func GetUserIDFromClaims(c *gin.Context) (string, error) {
	claims, exists := c.Get("claims")
	if !exists {
		return "", fmt.Errorf("no claims found in context")
	}

	mapClaims, ok := claims.(jwt.MapClaims)
	if !ok {
		return "", fmt.Errorf("invalid claims type in context")
	}

	userID, ok := mapClaims["user_id"].(string)
	if !ok {
		return "", fmt.Errorf("user_id not found in claims or invalid type")
	}

	return userID, nil
}

// GetCustomClaims extracts all custom claims from the context
func GetCustomClaims(c *gin.Context) (*CustomClaims, error) {
	claims, exists := c.Get("claims")
	if !exists {
		return nil, fmt.Errorf("no claims found in context")
	}

	mapClaims, ok := claims.(jwt.MapClaims)
	if !ok {
		return nil, fmt.Errorf("invalid claims type in context")
	}

	customClaims := &CustomClaims{
		MapClaims: mapClaims,
	}

	// Extract typed fields
	if userID, ok := mapClaims["user_id"].(string); ok {
		customClaims.UserID = userID
	}
	if role, ok := mapClaims["role"].(float64); ok { // JWT numbers are float64
		customClaims.Role = int32(role)
	}
	if email, ok := mapClaims["email"].(string); ok {
		customClaims.Email = email
	}

	return customClaims, nil
}
