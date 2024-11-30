package scanner

import (
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"
)

type BookMetadata struct {
	Title         string
	Authors       []Author
	Series        string
	SeriesIndex   float64
	PublishYear   int
	Subtitle      string
	Narrator      string
	Path          string
	HasSeriesInfo bool
}

type Author struct {
	FirstName string
	LastName  string
}

type DirectoryParser struct {
	parseSubtitles bool // Configuration option
}

func NewDirectoryParser(parseSubtitles bool) *DirectoryParser {
	return &DirectoryParser{
		parseSubtitles: parseSubtitles,
	}
}

func (p *DirectoryParser) ParseBookPath(path string) (BookMetadata, error) {
	parts := strings.Split(path, string(filepath.Separator))
	metadata := BookMetadata{Path: path}

	// Need at least 2 parts: root/book.m4b or root/folder/book.m4b
	if len(parts) < 2 {
		return metadata, nil
	}

	// Last part is the file
	fileIndex := len(parts) - 1
	if !isAudioFile(parts[fileIndex]) {
		return metadata, nil
	}

	// Process from deepest level up to first folder
	currentDepth := fileIndex - 1

	// Track when we find different components
	var foundTitle, foundAuthor, foundSeries bool

	for i := currentDepth; i >= 1; i-- {
		part := parts[i]

		// Skip special folders
		if part == "audiotrack" || part == "crop_original" {
			continue
		}

		// Try to parse as a title folder first
		if !foundTitle {
			if title := p.parseTitleFolder(part); title != nil {
				metadata.Title = title.Title
				metadata.PublishYear = title.Year
				metadata.SeriesIndex = title.SeriesIndex
				metadata.HasSeriesInfo = title.HasSeriesInfo
				metadata.Subtitle = title.Subtitle
				metadata.Narrator = title.Narrator
				foundTitle = true

				// If this has series info and the parent folder exists,
				// that parent should be the series
				if metadata.HasSeriesInfo && i > 1 {
					metadata.Series = parts[i-1]
					foundSeries = true
				}
				continue
			}
		}

		// If we haven't found an author yet, try parsing as author folder
		// Authors should be at the top level
		if !foundAuthor && i == 1 {
			if authors := parseAuthorFolder(part); len(authors) > 0 {
				metadata.Authors = authors
				foundAuthor = true
				continue
			}
		}

		// If we haven't found a series and this isn't the author folder,
		// and the next folder has series numbering, this is likely the series
		if !foundSeries && !foundAuthor && i > 1 {
			nextFolder := parts[i+1]
			if hasSeriesNumbering(nextFolder) {
				metadata.Series = part
				foundSeries = true
			}
		}
	}

	return metadata, nil
}

// Helper function to detect potential series folder
func isSeriesFolder(folderName string, nextFolder string) bool {
	// Common series name patterns
	commonSeriesPatterns := []string{
		// Direct indicators
		`(?i)series$`,
		`(?i)trilogy$`,
		`(?i)saga$`,
		`(?i)chronicles$`,
		`(?i)cycle$`,

		// Common series name formats
		`^The\s+\w+\s+of\s+\w+`,     // The Books of Babel, The Wheel of Time
		`^The\s+\w+\s+\w+`,          // The Dark Tower, The Solar Cycle
		`^(?:The\s+)?[A-Z][a-z]+.+`, // Mistborn, The Stormlight Archive
	}

	// Check if the folder name matches any series patterns
	for _, pattern := range commonSeriesPatterns {
		if matched, _ := regexp.MatchString(pattern, folderName); matched {
			return true
		}
	}

	// Check if the next folder has book numbering
	bookNumberPatterns := []string{
		`(?i)^book\s*\d+`,          // Book 1, Book 2
		`(?i)^vol(?:ume)?\s*\d+`,   // Volume 1, Vol 1
		`(?i)^\d+\s*[-\.]\s*`,      // 1 - , 2 -
		`(?i)^[a-z\s]+\d+\s*[-\.]`, // Book 1-, Volume 2-
	}

	if nextFolder != "" {
		for _, pattern := range bookNumberPatterns {
			if matched, _ := regexp.MatchString(pattern, nextFolder); matched {
				return true
			}
		}
	}

	return false
}

func hasSeriesNumbering(name string) bool {
	patterns := []string{
		// Match the exact patterns from the documentation
		`^Vol(?:ume)?\.?\s*\d+(?:\.\d+)?\s*[-\.]`,
		`^Book\s*\d+(?:\.\d+)?\s*[-\.]`,
		`^\d+(?:\.\d+)?\s*[-\.]`,
		`-\s*Vol(?:ume)?\.?\s*\d+(?:\.\d+)?(?:\s|$)`,
		`-\s*Book\s*\d+(?:\.\d+)?(?:\s|$)`,
	}

	for _, pattern := range patterns {
		if matched, _ := regexp.MatchString(`(?i)`+pattern, name); matched {
			return true
		}
	}
	return false
}

type titleInfo struct {
	Title         string
	Year          int
	SeriesIndex   float64
	HasSeriesInfo bool
	Subtitle      string
	Narrator      string
}

func (p *DirectoryParser) parseTitleFolder(name string) *titleInfo {
	// Extract narrator if present (in curly braces)
	narrator := ""
	if strings.Contains(name, "{") && strings.Contains(name, "}") {
		start := strings.LastIndex(name, "{")
		end := strings.LastIndex(name, "}")
		if start < end {
			narrator = strings.TrimSpace(name[start+1 : end])
			name = strings.TrimSpace(name[:start] + name[end+1:])
		}
	}

	// Parse year
	year := 0
	yearPatterns := []string{
		`^(\d{4})\s*-\s*`, // 1994 - Book
		`^Vol(?:ume)?\.?\s*\d+(?:\.\d+)?\s*-\s*(\d{4})`, // Vol 1 - 1994
		`^\((\d{4})\)`, // (1994)
	}

	for _, pattern := range yearPatterns {
		if re := regexp.MustCompile(pattern); re != nil {
			if matches := re.FindStringSubmatch(name); len(matches) > 1 {
				year = parseYear(matches[1])
				name = strings.TrimSpace(re.ReplaceAllString(name, ""))
				break
			}
		}
	}

	// Parse series index
	seriesIndex := 0.0
	hasSeriesInfo := false
	indexPatterns := []struct {
		pattern string
		group   int
	}{
		{`^Vol(?:ume)?\.?\s*(\d+(?:\.\d+)?)\s*[-\.]`, 1},
		{`^Book\s*(\d+(?:\.\d+)?)\s*[-\.]`, 1},
		{`^(\d+(?:\.\d+)?)\s*[-\.]`, 1},
		{`-\s*Vol(?:ume)?\.?\s*(\d+(?:\.\d+)?)\s*$`, 1},
		{`-\s*Book\s*(\d+(?:\.\d+)?)\s*$`, 1},
	}

	for _, pattern := range indexPatterns {
		if re := regexp.MustCompile(pattern.pattern); re != nil {
			if matches := re.FindStringSubmatch(name); len(matches) > pattern.group {
				seriesIndex = parseFloat(matches[pattern.group])
				name = strings.TrimSpace(re.ReplaceAllString(name, ""))
				hasSeriesInfo = true
				break
			}
		}
	}

	// Extract subtitle if enabled
	subtitle := ""
	if p.parseSubtitles {
		parts := strings.Split(name, " - ")
		if len(parts) > 1 {
			name = parts[0]
			subtitle = strings.Join(parts[1:], " - ")
		}
	}

	return &titleInfo{
		Title:         strings.TrimSpace(name),
		Year:          year,
		SeriesIndex:   seriesIndex,
		HasSeriesInfo: hasSeriesInfo,
		Subtitle:      subtitle,
		Narrator:      narrator,
	}
}

// Helper function to clean up results
func cleanupTitle(title string) string {
	// Remove multiple spaces
	title = strings.Join(strings.Fields(title), " ")
	// Remove trailing/leading dashes and dots
	title = strings.Trim(title, " -.")
	return title
}

func parseAuthorFolder(name string) []Author {
	// Split on supported separators
	separators := []string{" and ", " & ", ", ", "; "}
	var authorNames []string

	for _, sep := range separators {
		if strings.Contains(name, sep) {
			authorNames = strings.Split(name, sep)
			break
		}
	}

	if len(authorNames) == 0 {
		authorNames = []string{name}
	}

	var authors []Author
	for _, authorName := range authorNames {
		authorName = strings.TrimSpace(authorName)
		if strings.Contains(authorName, ", ") {
			// Last, First format
			parts := strings.Split(authorName, ", ")
			if len(parts) == 2 {
				authors = append(authors, Author{
					LastName:  parts[0],
					FirstName: parts[1],
				})
			}
		} else {
			// First Last format
			parts := strings.Fields(authorName)
			if len(parts) > 1 {
				authors = append(authors, Author{
					FirstName: strings.Join(parts[:len(parts)-1], " "),
					LastName:  parts[len(parts)-1],
				})
			}
		}
	}

	return authors
}

func parseYear(year string) int {
	if y, err := time.Parse("2006", year); err == nil {
		return y.Year()
	}
	return 0
}

func parseFloat(num string) float64 {
	if f, err := strconv.ParseFloat(num, 64); err == nil {
		return f
	}
	return 0
}

func isAudioFile(name string) bool {
	ext := strings.ToLower(filepath.Ext(name))
	return ext == ".mp3" || ext == ".m4a" || ext == ".m4b" || ext == ".flac" || ext == ".ogg"
}
