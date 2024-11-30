package util

import (
	"bytes"
	"fmt"
	"github.com/chai2010/webp"
	"github.com/disintegration/imaging"
	"image"
	_ "image/gif"  // Register GIF format
	_ "image/jpeg" // Register JPEG format
	_ "image/png"  // Register PNG format
	"mime"
	"strings"
)

// ImageProcessor handles generic image processing operations
type ImageProcessor struct {
	WebPQuality    float32
	NoiseReduction float64
	Sharpening     float64
}

// ProcessedImage represents the result of image processing
type ProcessedImage struct {
	Data       []byte
	Format     string
	Width      int
	Height     int
	Size       int64
	SourceType string // Original image format
}

// ImageVersion represents a resized variant of an image
type ImageVersion struct {
	Width  int
	Height int
	Scale  float64
	Data   []byte
	Size   int64
}

type ImageSize struct {
	Width  int
	Height int
	Scale  float64
	Suffix string
}

// NewImageProcessor creates a new image processor with given settings
func NewImageProcessor(webpQuality float32, noiseReduction, sharpening float64) *ImageProcessor {
	return &ImageProcessor{
		WebPQuality:    webpQuality,
		NoiseReduction: noiseReduction,
		Sharpening:     sharpening,
	}
}

// ProcessImage processes raw image data and returns the processed result
func (p *ImageProcessor) ProcessImage(data []byte, maxWidth, maxHeight int, mimeType string) (*ProcessedImage, error) {
	// First validate the image
	if err := p.ValidateImage(data, maxWidth*maxHeight*4, mimeType); err != nil {
		return nil, fmt.Errorf("invalid image data: %w", err)
	}

	// Process the raw image data and get the source image
	sourceImage, format, err := p.ProcessRawImage(data, mimeType)
	if err != nil {
		return nil, fmt.Errorf("failed to process raw image: %w", err)
	}

	// Apply optimizations
	processed := imaging.Clone(sourceImage)

	// Apply noise reduction if configured
	if p.NoiseReduction > 0 {
		processed = imaging.Blur(processed, p.NoiseReduction)
	}

	// Apply sharpening if configured
	if p.Sharpening > 0 {
		processed = imaging.Sharpen(processed, p.Sharpening)
	}

	// Apply standard optimizations
	processed = imaging.AdjustContrast(processed, 10)
	processed = imaging.AdjustBrightness(processed, 2)

	// Resize if needed
	if maxWidth > 0 || maxHeight > 0 {
		processed = imaging.Fit(processed, maxWidth, maxHeight, imaging.Lanczos)
	}

	// Convert to WebP
	buf := new(bytes.Buffer)
	options := &webp.Options{
		Lossless: false,
		Quality:  p.WebPQuality,
	}

	if err := webp.Encode(buf, processed, options); err != nil {
		return nil, fmt.Errorf("failed to encode WebP: %w", err)
	}

	webpData := buf.Bytes()
	newBounds := processed.Bounds()

	return &ProcessedImage{
		Data:       webpData,
		Format:     "image/webp",
		Width:      newBounds.Dx(),
		Height:     newBounds.Dy(),
		Size:       int64(len(webpData)),
		SourceType: format,
	}, nil
}

// CreateVersions generates multiple versions of an image
func (p *ImageProcessor) CreateVersions(img image.Image, sizes []ImageSize) ([]ImageVersion, error) {
	versions := make([]ImageVersion, 0, len(sizes))

	for _, size := range sizes {
		// Resize the image
		resized := imaging.Fit(img, size.Width, size.Height, imaging.Lanczos)

		// Convert to WebP
		buf := new(bytes.Buffer)
		options := &webp.Options{
			Lossless: false,
			Quality:  p.WebPQuality,
		}

		if err := webp.Encode(buf, resized, options); err != nil {
			return nil, fmt.Errorf("failed to create version %dx%d: %w",
				size.Width, size.Height, err)
		}

		webpData := buf.Bytes()
		bounds := resized.Bounds()

		versions = append(versions, ImageVersion{
			Width:  bounds.Dx(),
			Height: bounds.Dy(),
			Scale:  size.Scale,
			Data:   webpData,
			Size:   int64(len(webpData)),
		})
	}

	return versions, nil
}

// ValidateImage checks if the image meets basic requirements
func (p *ImageProcessor) ValidateImage(data []byte, maxSize int, mimeType string) error {
	if int(len(data)) > maxSize {
		return fmt.Errorf("image size exceeds maximum allowed size of %d bytes", maxSize)
	}

	// Clean and validate MIME type
	mimeType = strings.ToLower(strings.TrimSpace(mimeType))
	mediaType, _, err := mime.ParseMediaType(mimeType)
	if err != nil {
		return fmt.Errorf("invalid MIME type: %w", err)
	}

	// Check if MIME type is supported
	switch mediaType {
	case "image/jpeg", "image/jpg", "image/png", "image/gif", "image/webp":
		return nil
	default:
		return fmt.Errorf("unsupported image format: %s", mediaType)
	}
}

func (p *ImageProcessor) ProcessRawImage(data []byte, mimeType string) (image.Image, string, error) {
	mimeType = strings.ToLower(strings.TrimSpace(mimeType))

	// For JPEG files, attempt to find the actual JPEG data
	if mimeType == "image/jpeg" || mimeType == "image/jpg" {
		data = findJPEGData(data)
	}

	// Try to decode the image
	reader := bytes.NewReader(data)
	img, format, err := image.Decode(reader)
	if err != nil {
		return nil, "", fmt.Errorf("failed to decode image: %w", err)
	}

	return img, format, nil
}

func findJPEGData(data []byte) []byte {
	// JPEG markers
	jpegSOIMarker := []byte{0xFF, 0xD8} // Start of Image
	jpegEOIMarker := []byte{0xFF, 0xD9} // End of Image

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
