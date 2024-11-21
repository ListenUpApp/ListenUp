package handler

import (
	"fmt"
	"github.com/ListenUpApp/ListenUp/internal/models"
	"github.com/gin-gonic/gin"
	"net/http"
)

func (h *Handler) Ping(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{
		"success": true,
		"data": gin.H{
			"message": "pong",
		},
	})
}

func (h *Handler) Register(c *gin.Context) {
	var createUserReq models.RegisterRequest

	// Bind JSON to struct
	if err := c.ShouldBindJSON(&createUserReq); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{
			"error": err.Error(),
		})
		return
	}
	err := h.authService.RegisterUser(c, createUserReq)
	if err != nil {
		fmt.Errorf("an error ocurred")
	}
	c.JSON(http.StatusOK, gin.H{
		"success": true,
	})
}
