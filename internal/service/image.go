package service

import (
	"bytes"
	"fmt"
	"github.com/ListenUpApp/ListenUp/internal/config"
	logging "github.com/ListenUpApp/ListenUp/internal/logger"
	"github.com/ListenUpApp/ListenUp/pkg/taggart"
	"github.com/chai2010/webp"
	"github.com/disintegration/imaging"
	"image"
	"os"
	"path/filepath"
	"strings"
)

type ImageService struct {
	config config.MetadataConfig
	logger *logging.AppLogger
}

type ImageVersion struct {
	Path          string
	LogicalWidth  int
	LogicalHeight int
	ActualWidth   int
	ActualHeight  int
	Scale         float64
	Size          int64
	Suffix        string
}

type ProcessedImage struct {
	OriginalPath string
	Format       string
	Size         int64
	Versions     []ImageVersion
}

// JPEG markers
var (
	jpegSOIMarker = []byte{0xFF, 0xD8} // Start of Image
	jpegEOIMarker = []byte{0xFF, 0xD9} // End of Image
)

// Standard image sizes with HiDPI variants
var defaultSizes = []struct {
	width  int
	height int
	scale  float64
	suffix string
}{
	// Thumbnail
	{150, 150, 1.0, "thumbnail"},
	{150, 150, 2.0, "thumbnail@2x"},

	// Small
	{300, 300, 1.0, "small"},
	{300, 300, 2.0, "small@2x"},

	// Medium
	{600, 600, 1.0, "medium"},
	{600, 600, 2.0, "medium@2x"},

	// Large
	{1200, 1200, 1.0, "large"},
	{1200, 1200, 2.0, "large@2x"},
}

func NewImageService(cfg config.MetadataConfig, logger *logging.AppLogger) (*ImageService, error) {
	if logger == nil {
		return nil, fmt.Errorf("logger is required")
	}

	// Get home directory
	homeDir, err := os.UserHomeDir()
	if err != nil {
		return nil, fmt.Errorf("failed to get home directory: %w", err)
	}

	// Replace ~ with actual home directory if present
	basePath := cfg.BasePath
	if strings.HasPrefix(basePath, "~/") {
		basePath = filepath.Join(homeDir, basePath[2:])
	} else if basePath == "" {
		// If no path specified, use default
		basePath = filepath.Join(homeDir, "ListenUp", "metadata")
	}

	// Ensure the path is absolute
	if !filepath.IsAbs(basePath) {
		absPath, err := filepath.Abs(basePath)
		if err != nil {
			return nil, fmt.Errorf("failed to get absolute path: %w", err)
		}
		basePath = absPath
	}

	// Clean the path to remove any double slashes or other irregularities
	basePath = filepath.Clean(basePath)

	logger.Info("Setting up image service directories",
		"base_path", basePath)

	// Create required directories
	dirs := []string{
		basePath,
		filepath.Join(basePath, "covers"),
		filepath.Join(basePath, "temp"),
	}

	for _, dir := range dirs {
		if err := os.MkdirAll(dir, 0755); err != nil {
			return nil, fmt.Errorf("failed to create directory %s: %w", dir, err)
		}
		logger.Info("Created directory", "path", dir)
	}

	return &ImageService{
		config: config.MetadataConfig{
			BasePath:    basePath,
			WebPQuality: cfg.WebPQuality,
		},
		logger: logger,
	}, nil
}

func (s *ImageService) optimizeImage(img image.Image) image.Image {
	result := imaging.Clone(img)

	if s.config.NoiseReduction > 0 {
		result = imaging.Blur(result, s.config.NoiseReduction)
	}

	if s.config.Sharpening > 0 {
		result = imaging.Sharpen(result, s.config.Sharpening)
	}

	// Apply additional optimizations
	result = imaging.AdjustContrast(result, 10)
	result = imaging.AdjustBrightness(result, 2)

	return result
}

func (s *ImageService) resizeImage(img image.Image, width, height int) image.Image {
	return imaging.Fit(img, width, height, imaging.Lanczos)
}

// saveWebP saves an image in WebP format
// Also add logging to saveWebP
func (s *ImageService) saveWebP(img image.Image, path string, quality float32) error {
	s.logger.Debug("Creating WebP file",
		"path", path,
		"quality", quality)

	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0755); err != nil {
		return fmt.Errorf("failed to create directory %s: %w", dir, err)
	}

	f, err := os.Create(path)
	if err != nil {
		return fmt.Errorf("failed to create file: %w", err)
	}
	defer f.Close()

	options := &webp.Options{
		Lossless: false,
		Quality:  quality,
	}

	if err := webp.Encode(f, img, options); err != nil {
		return fmt.Errorf("failed to encode WebP: %w", err)
	}

	s.logger.Debug("Successfully wrote WebP file",
		"path", path)

	return nil
}

func (s *ImageService) findJPEGData(data []byte) []byte {
	// Look for JPEG SOI marker
	start := bytes.Index(data, jpegSOIMarker)
	if start == -1 {
		return data // No JPEG header found, return original data
	}

	// Look for JPEG EOI marker
	end := bytes.LastIndex(data, jpegEOIMarker)
	if end == -1 {
		return data[start:] // No end marker, return from start to end
	}

	// Return the data between (and including) the markers
	return data[start : end+2]
}

func (s *ImageService) ProcessCoverImage(coverData *taggart.Picture, bookID string) (*ProcessedImage, error) {
	if coverData == nil {
		return nil, nil
	}

	if bookID == "" {
		return nil, fmt.Errorf("book ID is required")
	}

	s.logger.Info("Starting cover image processing",
		"mime_type", coverData.MIMEType,
		"data_size", len(coverData.Data),
		"book_id", bookID,
		"base_path", s.config.BasePath)

	// Verify and process the image data
	imageData := coverData.Data
	if coverData.MIMEType == "image/jpeg" || coverData.MIMEType == "image/jpg" {
		imageData = s.findJPEGData(imageData)
	}

	// Decode the image
	reader := bytes.NewReader(imageData)
	sourceImage, _, err := image.Decode(reader)
	if err != nil {
		return nil, fmt.Errorf("failed to decode image: %w", err)
	}

	// Create paths using bookID
	relativeDir := filepath.Join("covers", bookID)
	absoluteDir := filepath.Join(s.config.BasePath, relativeDir)
	absoluteDir = filepath.Clean(absoluteDir)

	s.logger.Info("Creating cover directory",
		"relative_dir", relativeDir,
		"absolute_dir", absoluteDir)

	if err := os.MkdirAll(absoluteDir, 0755); err != nil {
		s.logger.Error("Failed to create directory",
			"path", absoluteDir,
			"error", err)
		return nil, fmt.Errorf("failed to create cover directory: %w", err)
	}

	processed := &ProcessedImage{
		Format:       "image/webp",
		OriginalPath: "/media/" + relativeDir + "/original.webp",
		Versions:     make([]ImageVersion, 0),
	}

	// Save original file
	originalFilePath := filepath.Join(absoluteDir, "original.webp")
	s.logger.Info("Saving original file",
		"path", originalFilePath)

	if err := s.saveWebP(sourceImage, originalFilePath, s.config.WebPQuality); err != nil {
		return nil, fmt.Errorf("failed to save original: %w", err)
	}

	// Get and log the original file size
	originalFileInfo, err := os.Stat(originalFilePath)
	if err != nil {
		return nil, fmt.Errorf("failed to get original file info: %w", err)
	}
	processed.Size = originalFileInfo.Size()

	s.logger.Info("Original file saved",
		"path", originalFilePath,
		"size", processed.Size)

	// Generate versions
	for _, size := range defaultSizes {
		resized := s.resizeImage(sourceImage, size.width, size.height)
		fileName := fmt.Sprintf("%s.webp", size.suffix)
		filePath := filepath.Join(absoluteDir, fileName)
		urlPath := "/media/" + filepath.Join(relativeDir, fileName)

		s.logger.Debug("Saving version",
			"size", fmt.Sprintf("%dx%d", size.width, size.height),
			"path", filePath)

		if err := s.saveWebP(resized, filePath, s.config.WebPQuality); err != nil {
			return nil, fmt.Errorf("failed to save %s version: %w", size.suffix, err)
		}

		fileInfo, err := os.Stat(filePath)
		if err != nil {
			return nil, fmt.Errorf("failed to get file info: %w", err)
		}

		s.logger.Info("Version file saved",
			"path", filePath,
			"size", fileInfo.Size(),
			"suffix", size.suffix)

		processed.Versions = append(processed.Versions, ImageVersion{
			Path:   urlPath,
			Size:   fileInfo.Size(),
			Suffix: size.suffix,
		})
	}

	return processed, nil
}
