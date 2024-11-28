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

	// Handle root-level audio files
	if len(parts) == 2 && isAudioFile(parts[1]) {
		metadata.Title = strings.TrimSuffix(parts[1], filepath.Ext(parts[1]))
		return metadata, nil
	}

	// Parse from deepest level up
	currentDepth := len(parts) - 1
	for i := currentDepth; i >= 0; i-- {
		part := parts[i]

		// Skip audio track folders
		if part == "audiotrack" || part == "crop_original" {
			continue
		}

		// Try parsing as title folder first
		if metadata.Title == "" {
			if title := p.parseTitleFolder(part); title != nil {
				metadata.Title = title.Title
				metadata.PublishYear = title.Year
				metadata.SeriesIndex = title.SeriesIndex
				metadata.HasSeriesInfo = title.HasSeriesInfo
				metadata.Subtitle = title.Subtitle
				metadata.Narrator = title.Narrator
				continue
			}
		}

		// Try parsing as series folder
		if metadata.Series == "" && !strings.Contains(part, "{") && !strings.Contains(part, "(") {
			metadata.Series = part
			continue
		}

		// Try parsing as author folder
		if len(metadata.Authors) == 0 {
			if authors := parseAuthorFolder(part); len(authors) > 0 {
				metadata.Authors = authors
				continue
			}
		}
	}

	return metadata, nil
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
	// Extract narrator if present
	narrator := ""
	if strings.Contains(name, "{") && strings.Contains(name, "}") {
		start := strings.LastIndex(name, "{")
		end := strings.LastIndex(name, "}")
		if start < end {
			narrator = strings.TrimSpace(name[start+1 : end])
			name = strings.TrimSpace(name[:start] + name[end+1:])
		}
	}

	// Extract year if present
	year := 0
	yearRegex := regexp.MustCompile(`^(?:\(?(\d{4})\)?\s*-\s*)|(?:(?:Vol\.?\s*\d+|\d+)\s*-\s*(\d{4})\s*-\s*)`)
	if matches := yearRegex.FindStringSubmatch(name); len(matches) > 0 {
		if matches[1] != "" {
			year = parseYear(matches[1])
			name = strings.TrimPrefix(name, matches[0])
		} else if matches[2] != "" {
			year = parseYear(matches[2])
			name = strings.TrimPrefix(name, matches[0])
		}
	}

	// Extract series index if present
	seriesIndex := 0.0
	hasSeriesInfo := false
	indexRegex := regexp.MustCompile(`(?i)^(?:(?:Vol(?:ume)?\.?\s*|Book\s*)?(\d+(?:\.\d+)?)\s*[-\.]\s*)|(?:\s*-\s*(?:Vol(?:ume)?\.?\s*|Book\s*)(\d+(?:\.\d+)?))`)
	if matches := indexRegex.FindStringSubmatch(name); len(matches) > 0 {
		if matches[1] != "" {
			seriesIndex = parseFloat(matches[1])
			name = strings.TrimPrefix(name, matches[0])
		} else if matches[2] != "" {
			seriesIndex = parseFloat(matches[2])
			name = strings.TrimSuffix(name, matches[0])
		}
		hasSeriesInfo = true
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

func parseAuthorFolder(name string) []Author {
	// Split on multiple author separators
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
			authors = append(authors, Author{
				LastName:  parts[0],
				FirstName: parts[1],
			})
		} else {
			// First Last format
			parts := strings.Split(authorName, " ")
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
