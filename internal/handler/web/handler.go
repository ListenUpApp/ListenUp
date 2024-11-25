package web

import (
	"log/slog"

	"github.com/ListenUpApp/ListenUp/internal/config"
	"github.com/ListenUpApp/ListenUp/internal/service"
	"github.com/ListenUpApp/ListenUp/internal/web/view/pages"
	"github.com/gin-gonic/gin"
	"github.com/go-playground/validator/v10"
)

type Handler struct {
	*BaseHandler
	auth    *AuthHandler
	library *LibraryHandler
	folder  *FolderHandler
}

type Config struct {
	Services  *service.Services
	Logger    *slog.Logger
	Config    *config.Config
	Validator *validator.Validate
}

func NewHandler(cfg Config) *Handler {
	baseHandler := &BaseHandler{
		logger:    cfg.Logger,
		services:  cfg.Services,
		config:    cfg.Config,
		validator: cfg.Validator,
	}
	return &Handler{
		BaseHandler: baseHandler,
		auth:        NewAuthHandler(cfg, baseHandler),
		library:     NewLibraryHandler(cfg, baseHandler),
		folder:      NewFolderHandler(cfg, baseHandler),
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
	folder := r.Group("/folder")
	{
		h.folder.RegisterRoutes(folder)
	}
}

func (h *Handler) HomePage(c *gin.Context) {
	err := h.RenderPage(c, "Home",
		pages.HomePage(),
		pages.HomeContent())

	if err != nil {
		return
	}
}
