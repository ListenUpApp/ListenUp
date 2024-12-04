package util

import (
	"bytes"
	"errors"
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

var (
	ErrInvalidJPEG       = errors.New("invalid JPEG data")
	ErrCorruptImage      = errors.New("corrupt image data")
	ErrUnsupportedFormat = errors.New("unsupported image format")
)

// ImageProcessor handles generic image processing operations
type ImageProcessor struct {
	WebPQuality    float32
	NoiseReduction float64
	Sharpening     float64
	MaxImageSize   int
}

// ProcessedImage represents the result of image processing
type ProcessedImage struct {
	Data       []byte
	Format     string
	Width      int
	Height     int
	Size       int64
	SourceType string
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
		MaxImageSize:   10 * 1024 * 1024,
	}
}

// ProcessImage processes raw image data and returns the processed result
func (p *ImageProcessor) ProcessImage(data []byte, maxWidth, maxHeight int, mimeType string) (*ProcessedImage, error) {
	// Validate basic image properties
	if err := p.ValidateImage(data, p.MaxImageSize, mimeType); err != nil {
		return nil, fmt.Errorf("image validation failed: %w", err)
	}

	// First try processing as is
	sourceImage, format, err := p.ProcessRawImage(data, mimeType)
	if err != nil {
		// If initial processing fails, try recovery methods
		sourceImage, format, err = p.attemptImageRecovery(data, mimeType)
		if err != nil {
			return nil, fmt.Errorf("image recovery failed: %w", err)
		}
	}

	// Process the image with safety checks
	processed, err := p.safelyProcessImage(sourceImage, maxWidth, maxHeight)
	if err != nil {
		return nil, fmt.Errorf("image processing failed: %w", err)
	}

	// Encode to WebP with safety checks
	webpData, err := p.encodeToWebP(processed)
	if err != nil {
		return nil, fmt.Errorf("WebP encoding failed: %w", err)
	}

	bounds := processed.Bounds()
	return &ProcessedImage{
		Data:       webpData,
		Format:     "image/webp",
		Width:      bounds.Dx(),
		Height:     bounds.Dy(),
		Size:       int64(len(webpData)),
		SourceType: format,
	}, nil
}

func (p *ImageProcessor) attemptImageRecovery(data []byte, mimeType string) (image.Image, string, error) {
	// Try different recovery methods based on mime type
	switch strings.ToLower(mimeType) {
	case "image/jpeg", "image/jpg":
		return p.recoverJPEG(data)
	case "image/png":
		return p.recoverPNG(data)
	default:
		return nil, "", ErrUnsupportedFormat
	}
}

func (p *ImageProcessor) recoverJPEG(data []byte) (image.Image, string, error) {
	// JPEG markers
	soiMarker := []byte{0xFF, 0xD8}
	eoiMarker := []byte{0xFF, 0xD9}

	// Find all potential JPEG segments
	var validSegments [][]byte

	start := 0
	for {
		start = bytes.Index(data[start:], soiMarker)
		if start == -1 {
			break
		}

		// Look for EOI marker after this SOI
		end := bytes.Index(data[start:], eoiMarker)
		if end == -1 {
			break
		}
		end += start + 2 // Include EOI marker

		segment := data[start:end]
		if isValidJPEGStructure(segment) {
			validSegments = append(validSegments, segment)
		}

		start = end
	}

	// Try each valid segment
	var lastErr error
	for _, segment := range validSegments {
		img, err := imaging.Decode(bytes.NewReader(segment))
		if err == nil {
			return img, "jpeg", nil
		}
		lastErr = err
	}

	return nil, "", fmt.Errorf("no valid JPEG segments found: %w", lastErr)
}

func (p *ImageProcessor) recoverPNG(data []byte) (image.Image, string, error) {
	// PNG signature
	pngSignature := []byte{0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}

	start := bytes.Index(data, pngSignature)
	if start == -1 {
		return nil, "", ErrCorruptImage
	}

	// Try to decode from the signature
	img, err := imaging.Decode(bytes.NewReader(data[start:]))
	if err != nil {
		return nil, "", fmt.Errorf("PNG recovery failed: %w", err)
	}

	return img, "png", nil
}

func isValidJPEGStructure(data []byte) bool {
	if len(data) < 4 {
		return false
	}

	// Check SOI marker
	if data[0] != 0xFF || data[1] != 0xD8 {
		return false
	}

	// Check for valid JPEG segment structure
	pos := 2
	for pos < len(data)-1 {
		if data[pos] != 0xFF {
			return false
		}
		pos++

		// Skip padding
		for pos < len(data) && data[pos] == 0xFF {
			pos++
		}

		if pos >= len(data) {
			return false
		}

		marker := data[pos]
		pos++

		// EOI marker
		if marker == 0xD9 {
			return pos == len(data)
		}

		// Skip standalone markers
		if marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7) {
			continue
		}

		// Read segment length
		if pos+1 >= len(data) {
			return false
		}

		length := int(data[pos])<<8 | int(data[pos+1])
		if length < 2 || pos+length > len(data) {
			return false
		}

		pos += length
	}

	return false
}

func (p *ImageProcessor) safelyProcessImage(img image.Image, maxWidth, maxHeight int) (image.Image, error) {
	if img == nil {
		return nil, errors.New("nil image provided")
	}

	bounds := img.Bounds()
	if bounds.Dx() <= 0 || bounds.Dy() <= 0 {
		return nil, errors.New("invalid image dimensions")
	}

	// Clone with safety check
	processed := imaging.Clone(img)
	if processed == nil {
		return nil, errors.New("failed to clone image")
	}

	// Apply optimizations with bounds checking
	if p.NoiseReduction > 0 {
		processed = imaging.Blur(processed, p.NoiseReduction)
	}

	if p.Sharpening > 0 {
		processed = imaging.Sharpen(processed, p.Sharpening)
	}

	processed = imaging.AdjustContrast(processed, 10)
	processed = imaging.AdjustBrightness(processed, 2)

	// Safe resize
	if maxWidth > 0 || maxHeight > 0 {
		if maxWidth <= 0 {
			maxWidth = bounds.Dx()
		}
		if maxHeight <= 0 {
			maxHeight = bounds.Dy()
		}

		processed = imaging.Fit(processed, maxWidth, maxHeight, imaging.Lanczos)
	}

	return processed, nil
}

func (p *ImageProcessor) encodeToWebP(img image.Image) ([]byte, error) {
	if img == nil {
		return nil, errors.New("nil image provided for WebP encoding")
	}

	buf := new(bytes.Buffer)
	options := &webp.Options{
		Lossless: false,
		Quality:  p.WebPQuality,
	}

	if err := webp.Encode(buf, img, options); err != nil {
		return nil, fmt.Errorf("WebP encoding failed: %w", err)
	}

	return buf.Bytes(), nil
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
