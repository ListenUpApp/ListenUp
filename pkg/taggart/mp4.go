// Copyright 2015, David Howden
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package taggart

import (
	"bytes"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"math"
	"regexp"
	"strconv"
	"strings"
	"unicode"
)

var atomTypes = map[int]string{
	0:  "implicit", // automatic based on atom name
	1:  "text",
	13: "jpeg",
	14: "png",
	21: "uint8",
}

// NB: atoms does not include "----", this is handled separately
var atoms = atomNames(map[string]string{
	"\xa9alb": "album",
	"\xa9art": "artist",
	"\xa9ART": "artist",
	"aART":    "album_artist",
	"\xa9day": "year",
	"\xa9nam": "title",
	"\xa9gen": "genre",
	"trkn":    "track",
	"\xa9wrt": "composer",
	"\xa9too": "encoder",
	"cprt":    "copyright",
	"covr":    "picture",
	"\xa9grp": "grouping",
	"keyw":    "keyword",
	"\xa9lyr": "lyrics",
	"\xa9cmt": "comment",
	"\xa9mvn": "movement",
	"\xa9mvc": "total_mov",
	"\xa9mvi": "mov_index",
	"shwm":    "showMovement",
	"tmpo":    "tempo",
	"cpil":    "compilation",
	"disk":    "disc",
	"chpl":    "chapter",
	"tref":    "chapter_ref",
	"chap":    "chapter",
	"©chp":    "chapter",
	"catg":    "catg",
	"sbtl":    "subtitle",  // New field for subtitle
	"lang":    "language",  // New field for language
	"isbn":    "isbn",      // New field for ISBN
	"asin":    "asin",      // New field for ASIN
	"nrts":    "narrators", // New field for narrators
	"seqn":    "series-part",
})

var means = map[string]bool{
	"com.apple.iTunes":          true,
	"com.mixedinkey.mixedinkey": true,
	"com.serato.dj":             true,
}

// Detect PNG image if "implicit" class is used
var pngHeader = []byte{137, 80, 78, 71, 13, 10, 26, 10}
var _ Metadata = &metadataMP4{}

type atomNames map[string]string

func (f atomNames) Name(n string) []string {
	res := make([]string, 1)
	for k, v := range f {
		if v == n {
			res = append(res, k)
		}
	}
	return res
}

// metadataMP4 is the implementation of Metadata for MP4 tag (atom) data.
type metadataMP4 struct {
	fileType FileType
	data     map[string]interface{}
	duration int
	chapters []Chapter
}

// ReadAtoms reads MP4 metadata atoms from the io.ReadSeeker into a Metadata, returning
// non-nil error if there was a problem.
func ReadAtoms(r io.ReadSeeker) (Metadata, error) {
	m := &metadataMP4{
		data:     make(map[string]interface{}),
		fileType: UnknownFileType,
	}
	err := m.readAtoms(r)
	return m, err
}
func (m *metadataMP4) readAtoms(r io.ReadSeeker) error {
	for {
		name, size, err := readAtomHeader(r)
		if err != nil {
			if err == io.EOF {
				return nil
			}
			return err
		}

		switch name {
		case "mvhd":
			// Read duration from movie header atom
			err := m.readMHVDAtom(r, size)
			if err != nil {
				return err
			}
			continue

		case "meta":
			// next_item_id (int32)
			_, err := readBytes(r, 4)
			if err != nil {
				return err
			}
			fallthrough

		case "moov", "udta", "ilst", "trak", "mdia":
			err := m.readAtoms(r)
			if err != nil {
				return err
			}

		default:
			_, ok := atoms[name]
			var data []string
			if name == "----" {
				name, data, err = readCustomAtom(r, size)
				if err != nil {
					return err
				}

				if name != "----" {
					ok = true
					size = 0 // already read data
				}
			}

			if !ok {
				_, err := r.Seek(int64(size-8), io.SeekCurrent)
				if err != nil {
					return err
				}
				continue
			}

			err = m.readAtomData(r, name, size-8, data)
			if err != nil {
				return err
			}
		}
	}
}

func (m *metadataMP4) readAtomData(r io.ReadSeeker, name string, size uint32, processedData []string) error {
	var b []byte
	var err error
	var contentType string

	if len(processedData) > 0 {
		b = []byte(strings.Join(processedData, ";"))
		contentType = "text"
	} else {
		b, err = readBytes(r, uint(size))
		if err != nil {
			return err
		}

		if name == "chpl" {
			contentType = "chapter"
		} else if name == "tref" || name == "chap" {
			contentType = "implicit"
		} else {
			if len(b) < 8 {
				contentType = "implicit"
			} else {
				dataWithHeader := make([]byte, len(b))
				copy(dataWithHeader, b)

				b = b[8:]
				if len(b) < 4 {
					contentType = "implicit"
				} else {
					class := getInt(b[1:4])
					var ok bool
					contentType, ok = atomTypes[class]
					if !ok {
						contentType = "implicit"
					}

					if contentType == "text" {
						b = dataWithHeader
					}
				}
			}
		}
	}

	var data interface{}
	switch contentType {
	case "text":
		data = string(b)
		if len(b) > 8 {
			data = string(b[8:])
		} else {
			data = string(b)
		}

		if name == "\xa9nam" {
			textContent := data.(string)
			textChapters, foundChapters := parseChapterFromText(textContent)

			if foundChapters {
				// Keep track of unique chapter titles to avoid duplicates
				titleExists := false
				for _, existing := range m.chapters {
					if existing.Title == textChapters[0].Title {
						titleExists = true
						break
					}
				}

				if !titleExists {
					m.chapters = append(m.chapters, textChapters...)

					// Recalculate timestamps for all chapters
					if m.duration > 0 && len(m.chapters) > 0 {
						chapterCount := len(m.chapters)
						for i := 0; i < chapterCount; i++ {
							startTimePercentage := float64(i) / float64(chapterCount)
							endTimePercentage := float64(i+1) / float64(chapterCount)

							m.chapters[i].Start = int64(float64(m.duration) * startTimePercentage)
							if i == chapterCount-1 {
								m.chapters[i].End = int64(m.duration)
							} else {
								m.chapters[i].End = int64(float64(m.duration) * endTimePercentage)
							}
						}
					}
				}
			}
		}

	case "chapter":
		fmt.Printf("Parsing chapters from chpl atom with %d bytes\n", len(b))
		chapters, err := parseChapters(b, int64(m.duration))
		if err != nil {
			return err
		}
		fmt.Printf("Found %d chapters in chpl atom\n", len(chapters))
		if len(chapters) > 0 {
			m.chapters = chapters
		}

	case "uint8":
		if len(b) < 1 {
			return fmt.Errorf("invalid encoding: expected at least %d bytes, for integer tag data, got %d", 1, len(b))
		}
		data = getInt(b[:1])

	case "jpeg", "png":
		data = &Picture{
			Ext:      contentType,
			MIMEType: "image/" + contentType,
			Data:     b,
		}
	}

	m.data[name] = data

	// Calculate timestamps after all chapters are collected
	if len(m.chapters) > 0 && m.duration > 0 {
		chapter_count := len(m.chapters)
		for i := 0; i < chapter_count; i++ {
			startTimePercentage := float64(i) / float64(chapter_count)
			endTimePercentage := float64(i+1) / float64(chapter_count)

			m.chapters[i].Start = int64(float64(m.duration) * startTimePercentage)
			if i == chapter_count-1 {
				m.chapters[i].End = int64(m.duration)
			} else {
				m.chapters[i].End = int64(float64(m.duration) * endTimePercentage)
			}
		}
	}

	return nil
}

func formatDuration(seconds int64) string {
	hours := seconds / 3600
	seconds -= hours * 3600
	minutes := seconds / 60
	seconds -= minutes * 60

	if hours > 0 {
		return fmt.Sprintf("%02d:%02d:%02d", hours, minutes, seconds)
	}
	return fmt.Sprintf("%02d:%02d", minutes, seconds)
}

func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}

func (m *metadataMP4) readMHVDAtom(r io.ReadSeeker, atomHeaderSize uint32) error {
	var b []byte
	var err error

	seekBytesLeft := int64(atomHeaderSize)

	// +1 byte, version
	b, err = readBytes(r, 1)
	if err != nil {
		return err
	}

	version := getInt(b[0:1])

	// +3 bytes, jump over flags
	_, err = r.Seek(3, io.SeekCurrent)
	if err != nil {
		return err
	}

	seekBytesLeft -= 4

	var duration int

	if version == 0 {
		// version 0 uses 32 bit integers for timestamps
		_, err = r.Seek(8, io.SeekCurrent)
		if err != nil {
			return err
		}

		timeScale, err := readUint32BigEndian(r)
		if err != nil {
			return err
		}

		dur, err := readUint32BigEndian(r)
		if err != nil {
			return err
		}

		seekBytesLeft -= 16

		// Calculate duration in seconds using timeScale
		duration = int(float64(dur) / float64(timeScale))
		fmt.Printf("Version 0 - Duration: %d, TimeScale: %d, Final Duration: %d seconds\n", dur, timeScale, duration)

	} else {
		// version 1 uses 64 bit integers for timestamps
		_, err = r.Seek(16, io.SeekCurrent)
		if err != nil {
			return err
		}

		timeScale, err := readUint32BigEndian(r)
		if err != nil {
			return err
		}

		dur, err := readUint64BigEndian(r)
		if err != nil {
			return err
		}

		seekBytesLeft -= 28

		// Calculate duration in seconds using timeScale
		duration = int(float64(dur) / float64(timeScale))
		fmt.Printf("Version 1 - Duration: %d, TimeScale: %d, Final Duration: %d seconds\n", dur, timeScale, duration)
	}

	m.duration = duration

	if _, err = r.Seek(seekBytesLeft-8, io.SeekCurrent); err != nil {
		return err
	}

	return nil
}

func parseTimeToSeconds(timeStr string) int64 {
	// Remove any leading/trailing spaces
	timeStr = strings.TrimSpace(timeStr)

	parts := strings.Split(timeStr, ":")
	if len(parts) == 2 {
		// MM:SS format
		minutes, _ := strconv.ParseInt(parts[0], 10, 64)
		seconds, _ := strconv.ParseInt(parts[1], 10, 64)
		return minutes*60 + seconds
	} else if len(parts) == 3 {
		// HH:MM:SS format
		hours, _ := strconv.ParseInt(parts[0], 10, 64)
		minutes, _ := strconv.ParseInt(parts[1], 10, 64)
		seconds, _ := strconv.ParseInt(parts[2], 10, 64)
		return hours*3600 + minutes*60 + seconds
	}
	return 0
}

func readAtomHeader(r io.ReadSeeker) (name string, size uint32, err error) {
	err = binary.Read(r, binary.BigEndian, &size)
	if err != nil {
		return
	}
	name, err = readString(r, 4)
	return
}

// Generic atom.
// Should have 3 sub atoms : mean, name and data.
// We check that mean is "com.apple.iTunes" or others and we use the subname as
// the name, and move to the data atom.
// Data atom could have multiple data values, each with a header.
// If anything goes wrong, we jump at the end of the "----" atom.
func readCustomAtom(r io.ReadSeeker, size uint32) (_ string, data []string, _ error) {
	subNames := make(map[string]string)

	for size > 8 {
		subName, subSize, err := readAtomHeader(r)
		if err != nil {
			return "", nil, err
		}

		// Remove the size of the atom from the size counter
		if size >= subSize {
			size -= subSize
		} else {
			return "", nil, errors.New("--- invalid size")
		}

		b, err := readBytes(r, uint(subSize-8))
		if err != nil {
			return "", nil, err
		}

		if len(b) < 4 {
			return "", nil, fmt.Errorf("invalid encoding: expected at least %d bytes, got %d", 4, len(b))
		}
		switch subName {
		case "mean", "name":
			subNames[subName] = string(b[4:])
		case "data":
			data = append(data, string(b[4:]))
		}
	}

	// there should remain only the header size
	if size != 8 {
		err := errors.New("---- atom out of bounds")
		return "", nil, err
	}

	if !means[subNames["mean"]] || subNames["name"] == "" || len(data) == 0 {
		return "----", nil, nil
	}

	return subNames["name"], data, nil
}

func (metadataMP4) Format() Format       { return MP4 }
func (m metadataMP4) FileType() FileType { return m.fileType }

func (m metadataMP4) Raw() map[string]interface{} { return m.data }

func (m metadataMP4) getString(n []string) string {
	for _, k := range n {
		if x, ok := m.data[k]; ok {
			return x.(string)
		}
	}
	return ""
}

func (m metadataMP4) getInt(n []string) int {
	for _, k := range n {
		if x, ok := m.data[k]; ok {
			return x.(int)
		}
	}
	return 0
}

func (m metadataMP4) Title() string {
	return m.getString(atoms.Name("title"))
}

func (m metadataMP4) Artist() string {
	return m.getString(atoms.Name("artist"))
}

func (m metadataMP4) Album() string {
	return m.getString(atoms.Name("album"))
}

func (m metadataMP4) AlbumArtist() string {
	return m.getString(atoms.Name("album_artist"))
}

func (m metadataMP4) Composer() string {
	return m.getString(atoms.Name("composer"))
}

func (m metadataMP4) Genre() string {
	return m.getString(atoms.Name("genre"))
}

func (m metadataMP4) Year() int {
	date := m.getString(atoms.Name("year"))
	if len(date) >= 4 {
		year, _ := strconv.Atoi(date[:4])
		return year
	}
	return 0
}

func (m metadataMP4) Track() (int, int) {
	x := m.getInt([]string{"trkn"})
	if n, ok := m.data["trkn_count"]; ok {
		return x, n.(int)
	}
	return x, 0
}

func (m metadataMP4) Disc() (int, int) {
	x := m.getInt([]string{"disk"})
	if n, ok := m.data["disk_count"]; ok {
		return x, n.(int)
	}
	return x, 0
}

func (m metadataMP4) Lyrics() string {
	t, ok := m.data["\xa9lyr"]
	if !ok {
		return ""
	}
	return t.(string)
}

func (m metadataMP4) Comment() string {
	t, ok := m.data["\xa9cmt"]
	if !ok {
		return ""
	}
	return t.(string)
}

func (m metadataMP4) Picture() *Picture {
	v, ok := m.data["covr"]
	if !ok {
		return nil
	}
	p, _ := v.(*Picture)
	return p
}
func (m metadataMP4) Duration() int {
	return m.duration
}

func (m metadataMP4) Chapters() []Chapter {
	return m.chapters
}

// Implement new methods for metadataMP4
func (m metadataMP4) Subtitle() string {
	if v, ok := m.data["sbtl"]; ok {
		return v.(string)
	}
	return ""
}

func (m metadataMP4) Publisher() string {
	if v, ok := m.data["cprt"]; ok {
		return v.(string)
	}
	return ""
}

func (m metadataMP4) Series() string {
	if v, ok := m.data["\xa9mvn"]; ok {
		return v.(string)
	}
	return ""
}

func (m metadataMP4) SeriesSequence() string {
	if v, ok := m.data["seqn"]; ok {
		return v.(string)
	}
	return ""
}

func (m metadataMP4) Language() string {
	if v, ok := m.data["lang"]; ok {
		return v.(string)
	}
	return ""
}

func (m metadataMP4) ISBN() string {
	if v, ok := m.data["isbn"]; ok {
		return v.(string)
	}
	return ""
}

func (m metadataMP4) ASIN() string {
	if v, ok := m.data["asin"]; ok {
		return v.(string)
	}
	return ""
}

func (m metadataMP4) Narrators() []string {
	if v, ok := m.data["nrts"]; ok {
		if arr, ok := v.([]string); ok {
			return arr
		}
	}
	// Fallback to composer if no explicit narrators field
	if composer := m.Composer(); composer != "" {
		return []string{composer}
	}
	return nil
}

// Chapter represents a chapter with start time, end time, and title.
type Chapter struct {
	id    uint8
	Start int64
	End   int64
	Title string
}

func parseChapters(data []byte, totalDuration int64) ([]Chapter, error) {
	var chapters []Chapter

	// Skip the 16-byte header
	if len(data) < 16 {
		return nil, fmt.Errorf("invalid chapter data: too short")
	}
	data = data[16:]

	// Process bytes until we can't find any more valid chapters
	pos := 0
	for pos < len(data) {
		// Need at least 4 bytes for timestamp and 2 bytes for minimal title
		if pos+6 > len(data) {
			break
		}

		var startTimeFloat float64
		var title string

		if len(chapters) == 0 {
			startTimeFloat = 0
			// Find the first valid chapter title
			title = extractNextChapterTitle(data[pos:])
			if title == "" {
				break
			}
			pos += len(title)
		} else {
			// Read timestamp (4 bytes)
			startTime := binary.BigEndian.Uint32(data[pos : pos+4])
			startTimeFloat = float64(startTime) * 256 / 10000000
			pos += 4

			// Skip any padding bytes (usually 2)
			for pos < len(data) && data[pos] == 0 {
				pos++
			}

			// Extract chapter title
			title = extractNextChapterTitle(data[pos:])
			if title == "" {
				break
			}
			pos += len(title)
		}

		// Clean and validate the title
		title = cleanChapterTitle(title)
		if title == "" {
			continue
		}

		// Skip any remaining separator bytes
		for pos < len(data) && (data[pos] == 0 || data[pos] < 32) {
			pos++
		}

		startTimeSeconds := int64(math.Round(startTimeFloat))

		chapter := Chapter{
			id:    uint8(len(chapters)),
			Title: title,
			Start: startTimeSeconds,
		}

		// Set end time of previous chapter
		if len(chapters) > 0 {
			chapters[len(chapters)-1].End = startTimeSeconds
		}

		chapters = append(chapters, chapter)
	}

	// Set end time of last chapter
	if len(chapters) > 0 {
		chapters[len(chapters)-1].End = totalDuration
	}

	return chapters, nil
}

// extractNextChapterTitle finds the next valid chapter title in the byte sequence
func extractNextChapterTitle(data []byte) string {
	// Common chapter start patterns
	patterns := []string{
		"Chapter ",
		"CHAPTER ",
		"Part ",
		"PART ",
		"Prologue",
		"PROLOGUE",
		"Epilogue",
		"EPILOGUE",
		"Introduction",
		"INTRODUCTION",
		"Interlude",
		"INTERLUDE",
		"End Credits",
		"END CREDITS",
	}

	// Look for the start of a chapter title
	var startIdx int = -1
	var matchedPattern string
	for _, pattern := range patterns {
		if idx := bytes.Index(data, []byte(pattern)); idx != -1 {
			if startIdx == -1 || idx < startIdx {
				startIdx = idx
				matchedPattern = pattern
			}
		}
	}

	if startIdx == -1 {
		return ""
	}

	// Find the end of the title by looking for the next non-printable character
	// or the start of another chapter pattern
	endIdx := startIdx + len(matchedPattern)
	for endIdx < len(data) {
		// Check if we've hit the start of another chapter
		for _, pattern := range patterns {
			if endIdx+len(pattern) <= len(data) && bytes.Equal(data[endIdx:endIdx+len(pattern)], []byte(pattern)) {
				return string(data[startIdx:endIdx])
			}
		}

		// Check for non-printable characters that might indicate the end of the title
		if data[endIdx] < 32 || data[endIdx] > 126 {
			return string(data[startIdx:endIdx])
		}

		endIdx++
	}

	// If we reached the end of data, return what we have
	return string(data[startIdx:endIdx])
}

func cleanChapterTitle(raw string) string {
	// Remove binary artifacts and control characters
	cleaned := strings.Map(func(r rune) rune {
		if r < 32 || r > 126 {
			return ' '
		}
		return r
	}, raw)

	// Replace multiple spaces with single space
	cleaned = regexp.MustCompile(`\s+`).ReplaceAllString(cleaned, " ")

	// Remove trailing or leading spaces
	cleaned = strings.TrimSpace(cleaned)

	// Remove trailing numbers and punctuation that might be artifacts
	cleaned = regexp.MustCompile(`[\d.,;:$@#%^&*\s]+$`).ReplaceAllString(cleaned, "")

	// Remove any binary artifacts that might appear as Unicode replacement characters
	cleaned = strings.ReplaceAll(cleaned, "�", "")

	// Check if the title is too corrupt
	if len(cleaned) <= 1 || isObviouslyCorrupted(cleaned) {
		return ""
	}

	return cleaned
}

func isObviouslyCorrupted(title string) bool {
	// Check for single characters or numbers
	if len(title) <= 2 {
		return true
	}

	// Check for titles that are just fragments
	fragments := []string{"ter", "apt", "art", "end", "the", "and", "for"}
	for _, fragment := range fragments {
		if strings.ToLower(title) == fragment {
			return true
		}
	}

	// Check if mostly symbols
	symbols := 0
	for _, r := range title {
		if !unicode.IsLetter(r) && !unicode.IsSpace(r) && !unicode.IsNumber(r) {
			symbols++
		}
	}
	if float64(symbols)/float64(len(title)) > 0.3 { // Reduced threshold to 30%
		return true
	}

	return false
}

func parseChapterFromText(text string) ([]Chapter, bool) {
	var chapters []Chapter

	// Common chapter indicators
	chapterIndicators := []string{
		"chapter",
		"track",
		"part",
		"section",
		"disc",
	}

	// Don't split on newlines since each title comes in one at a time
	text = strings.TrimSpace(text)
	if text == "" {
		return chapters, false
	}

	// Convert to lowercase for comparison
	lowerText := strings.ToLower(text)

	isChapter := false

	// Check for common chapter indicators
	for _, indicator := range chapterIndicators {
		if strings.Contains(lowerText, indicator) {
			isChapter = true
			break
		}
	}

	// Check for special sections that might be chapters but don't use common indicators
	if !isChapter {
		specialSections := []string{
			"intro",
			"introduction",
			"preface",
			"prologue",
			"epilogue",
			"afterword",
			"credits",
			"opening",
			"closing",
		}

		for _, section := range specialSections {
			if strings.Contains(lowerText, section) {
				isChapter = true
				break
			}
		}
	}

	if isChapter {
		chapters = append(chapters, Chapter{
			Title: text, // Use original text, not lowercase
		})
	}

	return chapters, len(chapters) > 0
}
