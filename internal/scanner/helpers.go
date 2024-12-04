package scanner

import (
	"bytes"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"
	"unicode"

	"github.com/ListenUpApp/ListenUp/internal/models"
	"github.com/ListenUpApp/ListenUp/pkg/taggart"
)

// processFile handles the processing of an audio file for metadata
func (s *Scanner) processFile(path string) {
	// Find parent folder and get relative path
	folderInfo, exists := s.findParentFolder(path)
	if !exists {
		s.logger.Debug("No parent folder found for file", "path", path)
		return
	}

	// Parse directory structure metadata
	fullPath := filepath.Join(folderInfo.folder.Path, folderInfo.relativePath)
	dirMetadata, err := s.parser.ParseBookPath(fullPath)
	if err != nil {
		s.logger.Error("Failed to parse book path",
			"path", fullPath,
			"error", err)
		return
	}

	// Open and read the audio file
	file, err := os.Open(path)
	if err != nil {
		s.logger.Error("Failed to open audio file",
			"path", path,
			"error", err)
		return
	}
	defer file.Close()

	fileInfo, err := file.Stat()
	if err != nil {
		s.logger.Error("Failed to get file info",
			"path", path,
			"error", err)
		return
	}

	// Read audio metadata
	audioMeta, err := taggart.ReadFrom(file)
	if err != nil {
		s.logger.Error("Failed to read audio metadata",
			"path", path,
			"error", err)
		return
	}

	// Build book data from combined metadata
	bookData, err := s.buildBookData(dirMetadata, audioMeta, fileInfo)
	if err != nil {
		s.logger.Error("Failed to build book data",
			"path", path,
			"error", err)
		return
	}

	// Create the book in the database
	if _, err := s.contentService.CreateBook(s.ctx, folderInfo.folder.ID, bookData); err != nil {
		s.logger.Error("Failed to create book",
			"path", fullPath,
			"error", err)
	}
}

// buildBookData combines directory and audio metadata into a CreateAudiobookRequest
func (s *Scanner) buildBookData(dirMeta BookMetadata, audioMeta taggart.Metadata, fileInfo os.FileInfo) (models.CreateAudiobookRequest, error) {
	// Process authors
	authors := s.processAuthors(dirMeta, audioMeta)

	// Process narrators
	narrators := s.processNarrators(dirMeta, audioMeta)

	// Process chapters
	chapters := s.processChapters(audioMeta)

	// Process cover art
	coverData := s.processCoverArt(audioMeta)

	// Process series information
	seriesName, seriesSequence := s.processSeriesInfo(dirMeta, audioMeta)

	return models.CreateAudiobookRequest{
		Title:          cleanString(audioMeta.Title()),
		Subtitle:       cleanString(dirMeta.Subtitle),
		Duration:       int64(audioMeta.Duration()),
		Size:           fileInfo.Size(),
		Description:    cleanString(audioMeta.Comment()),
		Isbn:           cleanString(audioMeta.ISBN()),
		Asin:           cleanString(audioMeta.ASIN()),
		Language:       cleanString(audioMeta.Language()),
		Publisher:      cleanString(audioMeta.Publisher()),
		PublishedDate:  time.Date(audioMeta.Year(), 1, 1, 0, 0, 0, 0, time.UTC),
		Genres:         []string{cleanString(audioMeta.Genre())},
		Authors:        authors,
		Narrators:      narrators,
		Chapter:        chapters,
		CoverData:      coverData,
		SeriesName:     seriesName,
		SeriesSequence: seriesSequence,
	}, nil
}

// processAuthors combines author information from both sources
func (s *Scanner) processAuthors(dirMeta BookMetadata, audioMeta taggart.Metadata) []models.CreateAuthorRequest {
	var authors []models.CreateAuthorRequest

	// Try directory metadata first
	if len(dirMeta.Authors) > 0 {
		for _, author := range dirMeta.Authors {
			authors = append(authors, models.CreateAuthorRequest{
				Name:        cleanString(fmt.Sprintf("%s %s", author.FirstName, author.LastName)),
				Description: "",
			})
		}
		return authors
	}

	// Fall back to audio metadata
	if audioMeta.Artist() != "" {
		for _, name := range splitAuthors(audioMeta.Artist()) {
			authors = append(authors, models.CreateAuthorRequest{
				Name:        cleanString(name),
				Description: "",
			})
		}
	}

	return authors
}

// processNarrators combines narrator information from both sources
func (s *Scanner) processNarrators(dirMeta BookMetadata, audioMeta taggart.Metadata) []models.CreateNarratorRequest {
	var narrators []models.CreateNarratorRequest
	seenNarrators := make(map[string]bool)

	// Helper to add unique narrators
	addNarrator := func(name string) {
		name = strings.TrimSpace(name)
		if name == "" || seenNarrators[name] {
			return
		}

		narrators = append(narrators, models.CreateNarratorRequest{
			Name:        name,
			Description: "",
		})
		seenNarrators[name] = true
	}

	// Process directory metadata first
	if dirMeta.Narrator != "" {
		for _, name := range splitNarrators(dirMeta.Narrator) {
			addNarrator(name)
		}
	}

	// Then process audio metadata
	for _, name := range audioMeta.Narrators() {
		for _, splitName := range splitNarrators(name) {
			addNarrator(splitName)
		}
	}

	// Sort narrators for consistent ordering
	sort.Slice(narrators, func(i, j int) bool {
		return narrators[i].Name < narrators[j].Name
	})

	return narrators
}

// processChapters extracts chapter information
func (s *Scanner) processChapters(audioMeta taggart.Metadata) []models.CreateChapterRequest {
	var chapters []models.CreateChapterRequest
	for _, chapter := range audioMeta.Chapters() {
		chapters = append(chapters, models.CreateChapterRequest{
			Title: cleanString(chapter.Title),
			Start: float64(chapter.Start),
			End:   float64(chapter.End),
		})
	}
	return chapters
}

// processCoverArt handles extraction and validation of cover art
func (s *Scanner) processCoverArt(audioMeta taggart.Metadata) *taggart.Picture {
	if picture := audioMeta.Picture(); picture != nil {
		return &taggart.Picture{
			Data:        findJPEGData(picture.Data),
			MIMEType:    picture.MIMEType,
			Ext:         picture.Ext,
			Type:        picture.Type,
			Description: picture.Description,
		}
	}
	return nil
}

// processSeriesInfo combines series information from both sources
func (s *Scanner) processSeriesInfo(dirMeta BookMetadata, audioMeta taggart.Metadata) (string, float64) {
	// Try audio metadata first
	if audioMeta.Series() != "" {
		sequence := 0.0
		if seqStr := audioMeta.SeriesSequence(); seqStr != "" {
			if seq, err := strconv.ParseFloat(seqStr, 64); err == nil {
				sequence = seq
			}
		}
		return audioMeta.Series(), sequence
	}

	// Fall back to directory metadata
	if dirMeta.HasSeriesInfo {
		return dirMeta.Series, dirMeta.SeriesIndex
	}

	return "", 0
}

// findJPEGData attempts to locate actual JPEG data within potentially wrapped image data
func findJPEGData(data []byte) []byte {
	// JPEG markers
	soiMarker := []byte{0xFF, 0xD8} // Start of Image
	eoiMarker := []byte{0xFF, 0xD9} // End of Image

	start := bytes.Index(data, soiMarker)
	if start == -1 {
		return data
	}

	end := bytes.LastIndex(data, eoiMarker)
	if end == -1 {
		return data[start:]
	}

	return data[start : end+2]
}

type folderInfo struct {
	folder       *models.Folder
	relativePath string
}

// findParentFolder locates the parent folder for a given path
func (s *Scanner) findParentFolder(path string) (*folderInfo, bool) {
	s.mutex.RLock()
	defer s.mutex.RUnlock()

	current := path
	for {
		if folder, exists := s.folderMap[current]; exists {
			relPath, err := filepath.Rel(current, path)
			if err != nil {
				s.logger.Error("Failed to get relative path",
					"folder", current,
					"file", path,
					"error", err)
				return nil, false
			}
			return &folderInfo{
				folder:       folder,
				relativePath: relPath,
			}, true
		}

		parent := filepath.Dir(current)
		if parent == current {
			break
		}
		current = parent
	}

	return nil, false
}

// isAudioFile checks if a file has a supported audio extension
func (s *Scanner) isAudioFile(path string) bool {
	return s.audioExts[strings.ToLower(filepath.Ext(path))]
}

// cleanString removes control characters and trims spaces
func cleanString(s string) string {
	// First pass: remove all non-printable characters except spaces
	cleaned := strings.Map(func(r rune) rune {
		if unicode.IsPrint(r) || unicode.IsSpace(r) {
			return r
		}
		return -1
	}, s)

	// Replace multiple spaces with single space
	cleaned = regexp.MustCompile(`\s+`).ReplaceAllString(cleaned, " ")

	// Remove any remaining control characters by explicit code point
	cleaned = strings.Map(func(r rune) rune {
		if r < 32 || r == 127 || (r >= 128 && r <= 159) {
			return -1
		}
		return r
	}, cleaned)

	// Handle any UTF-8 replacement characters
	cleaned = strings.ReplaceAll(cleaned, "�", "")

	// Trim spaces
	cleaned = strings.TrimSpace(cleaned)

	return cleaned
}

// splitAuthors splits an author string on multiple possible delimiters
func splitAuthors(authorStr string) []string {
	// First clean the input string
	authorStr = strings.TrimSpace(authorStr)
	if authorStr == "" {
		return nil
	}

	// Common cleanup replacements
	replacements := map[string]string{
		" and ": ", ",
		" & ":   ", ",
		"; ":    ", ",
	}

	// Apply replacements
	for old, new := range replacements {
		authorStr = strings.ReplaceAll(authorStr, old, new)
	}

	// Split on comma
	var authors []string
	for _, author := range strings.Split(authorStr, ",") {
		if cleaned := strings.TrimSpace(author); cleaned != "" {
			// Additional validation to catch obviously wrong splits
			if len(cleaned) > 2 && !strings.Contains(cleaned, ".") { // Avoid initials or abbreviated parts
				authors = append(authors, cleaned)
			}
		}
	}

	// If we ended up with no valid authors, return the original as a single author
	if len(authors) == 0 && authorStr != "" {
		return []string{authorStr}
	}

	return authors
}

// splitNarrators handles various narrator name formats
func splitNarrators(narratorStr string) []string {
	// First clean the input string
	narratorStr = strings.TrimSpace(narratorStr)
	if narratorStr == "" {
		return nil
	}

	// Map to track unique narrators while preserving case
	uniqueNarrators := make(map[string]string)

	// First split on explicit separators
	parts := strings.FieldsFunc(narratorStr, func(r rune) bool {
		return r == ',' || r == ';'
	})

	for _, part := range parts {
		// Further split on other separators
		subParts := strings.FieldsFunc(part, func(r rune) bool {
			return r == '/' || r == '&'
		})

		for _, subPart := range subParts {
			// Clean up each part
			name := normalizeNarratorName(subPart)
			if name == "" {
				continue
			}

			// Store with lowercase key for deduplication but preserve original case
			lowerName := strings.ToLower(name)
			if existingName, exists := uniqueNarrators[lowerName]; exists {
				// If we already have this name, keep the one with better capitalization
				if shouldReplaceExistingName(existingName, name) {
					uniqueNarrators[lowerName] = name
				}
			} else {
				uniqueNarrators[lowerName] = name
			}
		}
	}

	// Convert map to sorted slice
	var result []string
	for _, name := range uniqueNarrators {
		result = append(result, name)
	}
	sort.Strings(result)

	return result
}

func normalizeNarratorName(name string) string {
	// Remove common connecting words
	name = strings.TrimSpace(name)
	name = strings.NewReplacer(
		" and ", " ",
		" AND ", " ",
		" & ", " ",
		" - ", " ",
	).Replace(name)

	// Clean up whitespace
	name = strings.Join(strings.Fields(name), " ")

	// Skip if too short or just connecting words
	if len(name) <= 2 || isConnectingWord(name) {
		return ""
	}

	// Handle special case of initials
	if len(name) <= 3 && strings.HasSuffix(name, ".") {
		return name
	}

	// Preserve certain capitalization patterns
	words := strings.Fields(name)
	for i, word := range words {
		// Keep certain words lowercase
		if i > 0 && isCommonWord(word) {
			words[i] = strings.ToLower(word)
		} else {
			// Capitalize first letter of other words
			if len(word) > 0 {
				words[i] = strings.ToUpper(word[:1]) + strings.ToLower(word[1:])
			}
		}
	}

	return strings.Join(words, " ")
}

func isConnectingWord(word string) bool {
	connecting := map[string]bool{
		"and": true,
		"the": true,
		"by":  true,
		"of":  true,
	}
	return connecting[strings.ToLower(strings.TrimSpace(word))]
}

func isCommonWord(word string) bool {
	common := map[string]bool{
		"of":  true,
		"the": true,
		"in":  true,
		"on":  true,
		"at":  true,
		"to":  true,
	}
	return common[strings.ToLower(word)]
}

func shouldReplaceExistingName(existing, new string) bool {
	// Prefer names with better capitalization
	existingWords := strings.Fields(existing)
	newWords := strings.Fields(new)

	if len(existingWords) != len(newWords) {
		// Keep the longer version
		return len(newWords) > len(existingWords)
	}

	// Prefer names where more words start with capitals
	existingCaps := countCapitalizedWords(existing)
	newCaps := countCapitalizedWords(new)
	return newCaps > existingCaps
}

func countCapitalizedWords(s string) int {
	count := 0
	for _, word := range strings.Fields(s) {
		if len(word) > 0 && unicode.IsUpper(rune(word[0])) {
			count++
		}
	}
	return count
}
