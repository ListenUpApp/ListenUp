package handler

import (
	"github.com/gin-gonic/gin"
	"net/http"
)

type WebHandler struct {
}

func NewWebHandler() *WebHandler {
	return &WebHandler{}
}

func (h *WebHandler) RegisterPage(c *gin.Context) {
	c.HTML(http.StatusOK, "register.templ", gin.H{
		"title": "Register",
	})
}
