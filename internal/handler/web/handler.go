package web

import (
	"log/slog"

	"github.com/ListenUpApp/ListenUp/internal/config"
	appcontext "github.com/ListenUpApp/ListenUp/internal/context"
	"github.com/ListenUpApp/ListenUp/internal/service"
	"github.com/ListenUpApp/ListenUp/internal/web/view/pages"
	"github.com/a-h/templ"
	"github.com/gin-gonic/gin"
)

type Handler struct {
	auth     *AuthHandler
	library  *LibraryHandler
	services *service.Services
	logger   *slog.Logger
	config   *config.Config
}

type Config struct {
	Services *service.Services
	Logger   *slog.Logger
	Config   *config.Config
}

type RenderConfig struct {
	Writer    gin.ResponseWriter
	Logger    *slog.Logger
	Path      string
	PageTitle string
	IsHTMX    bool
}

func NewHandler(cfg Config) *Handler {
	return &Handler{
		auth:     NewAuthHandler(cfg),
		library:  NewLibraryHandler(cfg),
		services: cfg.Services,
		logger:   cfg.Logger,
		config:   cfg.Config,
	}
}

func (h *Handler) RegisterPublicRoutes(r *gin.RouterGroup) {
	h.logger.Info("registering public routes")
	auth := r.Group("/auth")
	{
		h.auth.RegisterRoutes(auth)
	}
}

func (h *Handler) RegisterProtectedRoutes(r *gin.RouterGroup) {
	r.GET("/", h.HomePage)
	library := r.Group("/library")
	{
		h.library.RegisterRoutes(library)
	}
}

func RenderPage(c *gin.Context, cfg RenderConfig, fullPage, partial templ.Component) error {
	appCtx, exists := appcontext.GetAppContextFromGin(c)
	if !exists {
		cfg.Logger.Error("app context not found")
		c.String(500, "Error: app context not found")
		return nil
	}

	// Create a new context with the app context value
	ctx := appcontext.WithAppContext(c.Request.Context(), appCtx)

	component := fullPage
	if cfg.IsHTMX {
		component = partial
	}

	err := component.Render(ctx, cfg.Writer)
	if err != nil {
		cfg.Logger.Error("error rendering page",
			"error", err,
			"path", cfg.Path)
		c.String(500, "Error rendering page")
	}

	return err
}

func (h *Handler) HomePage(c *gin.Context) {
	err := RenderPage(c, RenderConfig{
		Writer:    c.Writer,
		Logger:    h.logger,
		Path:      c.Request.URL.Path,
		PageTitle: "Home",
		IsHTMX:    c.GetHeader("HX-Request") == "true",
	},
		pages.HomePage("Home"),
		pages.HomeContent())

	if err != nil {
		return
	}
}
