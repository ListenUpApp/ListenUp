package handler

import (
	"github.com/ListenUpApp/ListenUp/web/view/pages"
	"github.com/gin-gonic/gin"
)

type Handler struct{}

func NewHandler() *Handler {
	return &Handler{}
}

func (h *Handler) RegisterRoutes(r *gin.Engine) {
	r.Static("/static", "./web/static")
	client := r.Group("")
	{
		client.GET("/", HomeHandler)
	}
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
