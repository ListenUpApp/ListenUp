package handler

import (
	"fmt"
	"github.com/ListenUpApp/ListenUp/internal/api/repository"
	"github.com/ListenUpApp/ListenUp/internal/web/view/pages"
	"github.com/ListenUpApp/ListenUp/internal/web/view/pages/auth"
	"github.com/gin-gonic/gin"
)

type Handler struct {
	serverRepo *repository.ServerRepository
}

func NewHandler(serverRepo repository.ServerRepository) *Handler {
	return &Handler{serverRepo: &serverRepo}
}

func (h *Handler) RegisterAppRoutes(r *gin.Engine) {
	r.Static("/static", "./internal/web/static")
	auth := r.Group("/auth")
	{
		auth.GET("/register", h.RegisterHandler)
	}
	client := r.Group("")
	{
		client.GET("/", HomeHandler)
	}
}

func (h *Handler) RegisterApiRoutes(r *gin.Engine) {
	api := r.Group("api/v1")

	public := api.Group("")
	{
		auth := public.Group("/auth")
		{
			auth.GET("/ping", h.Ping)
		}
	}
}

func HomeHandler(c *gin.Context) {
	component := pages.Home()
	component.Render(c.Request.Context(), c.Writer)
}

func (h *Handler) RegisterHandler(c *gin.Context) {
	srv, err := h.serverRepo.GetServer(c)
	if err != nil {
		fmt.Errorf("an error occured retrieving the server")
	}
	component := auth.Reigster(srv.Setup)
	component.Render(c.Request.Context(), c.Writer)
}
