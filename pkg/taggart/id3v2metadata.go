// Copyright 2015, David Howden
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package taggart

import (
	"strconv"
	"strings"
	"time"
)

type frameNames map[string][2]string

func (f frameNames) Name(s string, fm Format) string {
	l, ok := f[s]
	if !ok {
		return ""
	}

	switch fm {
	case ID3v2_2:
		return l[0]
	case ID3v2_3:
		return l[1]
	case ID3v2_4:
		if s == "year" {
			return "TDRC"
		}
		return l[1]
	}
	return ""
}

var frames = frameNames(map[string][2]string{
	"title":        [2]string{"TT2", "TIT2"},
	"artist":       [2]string{"TP1", "TPE1"},
	"album":        [2]string{"TAL", "TALB"},
	"album_artist": [2]string{"TP2", "TPE2"},
	"composer":     [2]string{"TCM", "TCOM"},
	"year":         [2]string{"TYE", "TYER"},
	"track":        [2]string{"TRK", "TRCK"},
	"disc":         [2]string{"TPA", "TPOS"},
	"genre":        [2]string{"TCO", "TCON"},
	"picture":      [2]string{"PIC", "APIC"},
	"lyrics":       [2]string{"", "USLT"},
	"comment":      [2]string{"COM", "COMM"},
})

// metadataID3v2 is the implementation of Metadata used for ID3v2 tags.
type metadataID3v2 struct {
	header *id3v2Header
	frames map[string]interface{}
}

func (m metadataID3v2) getString(k string) string {
	v, ok := m.frames[k]
	if !ok {
		return ""
	}
	return v.(string)
}

func (m metadataID3v2) Format() Format              { return m.header.Version }
func (m metadataID3v2) FileType() FileType          { return MP3 }
func (m metadataID3v2) Raw() map[string]interface{} { return m.frames }

func (m metadataID3v2) Title() string {
	return m.getString(frames.Name("title", m.Format()))
}

func (m metadataID3v2) Artist() string {
	return m.getString(frames.Name("artist", m.Format()))
}

func (m metadataID3v2) Album() string {
	return m.getString(frames.Name("album", m.Format()))
}

func (m metadataID3v2) AlbumArtist() string {
	return m.getString(frames.Name("album_artist", m.Format()))
}

func (m metadataID3v2) Composer() string {
	return m.getString(frames.Name("composer", m.Format()))
}

func (m metadataID3v2) Genre() string {
	return id3v2genre(m.getString(frames.Name("genre", m.Format())))
}

func (m metadataID3v2) Year() int {
	stringYear := m.getString(frames.Name("year", m.Format()))

	if year, err := strconv.Atoi(stringYear); err == nil {
		return year
	}

	date, err := time.Parse(time.DateOnly, stringYear)
	if err != nil {
		return 0
	}

	return date.Year()
}

func (m metadataID3v2) Duration() int {
	return 0
}

func parseXofN(s string) (x, n int) {
	xn := strings.Split(s, "/")
	if len(xn) != 2 {
		x, _ = strconv.Atoi(s)
		return x, 0
	}
	x, _ = strconv.Atoi(strings.TrimSpace(xn[0]))
	n, _ = strconv.Atoi(strings.TrimSpace(xn[1]))
	return x, n
}

func (m metadataID3v2) Track() (int, int) {
	return parseXofN(m.getString(frames.Name("track", m.Format())))
}

func (m metadataID3v2) Disc() (int, int) {
	return parseXofN(m.getString(frames.Name("disc", m.Format())))
}

func (m metadataID3v2) Lyrics() string {
	t, ok := m.frames[frames.Name("lyrics", m.Format())]
	if !ok {
		return ""
	}
	return t.(*Comm).Text
}

func (m metadataID3v2) Comment() string {
	t, ok := m.frames[frames.Name("comment", m.Format())]
	if !ok {
		return ""
	}
	// id3v23 has Text, id3v24 has Description
	if t.(*Comm).Description == "" {
		return trimString(t.(*Comm).Text)
	}
	return trimString(t.(*Comm).Description)
}

func (m metadataID3v2) Picture() *Picture {
	v, ok := m.frames[frames.Name("picture", m.Format())]
	if !ok {
		return nil
	}
	return v.(*Picture)
}

//TODO make sure these work:
func (m metadataID3v2) Subtitle() string {
    if v, ok := m.frames["TIT3"]; ok {
        return v.(string)
    }
    return ""
}

func (m metadataID3v2) Publisher() string {
    if v, ok := m.frames["TPUB"]; ok {
        return v.(string)
    }
    return ""
}

func (m metadataID3v2) Series() string {
    // Try MVNM (Movement Name) frame first
    if v, ok := m.frames["MVNM"]; ok {
        return v.(string)
    }
    // Fall back to TXXX frame with series identifier
    if v, ok := m.frames["TXXX_SERIES"]; ok {
        return v.(string)
    }
    return ""
}

func (m metadataID3v2) SeriesSequence() string {
    // Try MVIN (Movement Number) frame first
    if v, ok := m.frames["MVIN"]; ok {
        return v.(string)
    }
    // Fall back to TXXX frame with series-part identifier
    if v, ok := m.frames["TXXX_SERIES-PART"]; ok {
        return v.(string)
    }
    return ""
}

func (m metadataID3v2) Language() string {
    if v, ok := m.frames["TLAN"]; ok {
        return v.(string)
    }
    return ""
}

func (m metadataID3v2) ISBN() string {
    // Check for ISBN in custom TXXX frame
    if v, ok := m.frames["TXXX_ISBN"]; ok {
        return v.(string)
    }
    return ""
}

func (m metadataID3v2) ASIN() string {
    // Check for ASIN in custom TXXX frame
    if v, ok := m.frames["TXXX_ASIN"]; ok {
        return v.(string)
    }
    return ""
}

func (m metadataID3v2) Narrators() []string {
    // Try TOPE (Original Artist/Performer) frame first
    if v, ok := m.frames["TOPE"]; ok {
        return []string{v.(string)}
    }
    // Fall back to custom TXXX frame
    if v, ok := m.frames["TXXX_NARRATOR"]; ok {
        return []string{v.(string)}
    }
    return nil
}

func (m metadataID3v2) Chapters() []Chapter {
    // ID3v2 can store chapters using CHAP frames
    // This would require parsing the CHAP frame structure
    // For now, return nil as proper chapter support would need additional implementation
    return nil
}