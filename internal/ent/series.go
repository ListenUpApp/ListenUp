// Code generated by ent, DO NOT EDIT.

package ent

import (
	"fmt"
	"strings"
	"time"

	"entgo.io/ent"
	"entgo.io/ent/dialect/sql"
	"github.com/ListenUpApp/ListenUp/internal/ent/series"
)

// Series is the model entity for the Series schema.
type Series struct {
	config `json:"-"`
	// ID of the ent.
	ID string `json:"id,omitempty"`
	// Name holds the value of the "name" field.
	Name string `json:"name,omitempty"`
	// NameSort holds the value of the "name_sort" field.
	NameSort string `json:"name_sort,omitempty"`
	// Description holds the value of the "description" field.
	Description string `json:"description,omitempty"`
	// CreatedAt holds the value of the "created_at" field.
	CreatedAt time.Time `json:"created_at,omitempty"`
	// UpdatedAt holds the value of the "updated_at" field.
	UpdatedAt time.Time `json:"updated_at,omitempty"`
	// Edges holds the relations/edges for other nodes in the graph.
	// The values are being populated by the SeriesQuery when eager-loading is set.
	Edges        SeriesEdges `json:"edges"`
	selectValues sql.SelectValues
}

// SeriesEdges holds the relations/edges for other nodes in the graph.
type SeriesEdges struct {
	// SeriesBooks holds the value of the series_books edge.
	SeriesBooks []*SeriesBook `json:"series_books,omitempty"`
	// loadedTypes holds the information for reporting if a
	// type was loaded (or requested) in eager-loading or not.
	loadedTypes [1]bool
}

// SeriesBooksOrErr returns the SeriesBooks value or an error if the edge
// was not loaded in eager-loading.
func (e SeriesEdges) SeriesBooksOrErr() ([]*SeriesBook, error) {
	if e.loadedTypes[0] {
		return e.SeriesBooks, nil
	}
	return nil, &NotLoadedError{edge: "series_books"}
}

// scanValues returns the types for scanning values from sql.Rows.
func (*Series) scanValues(columns []string) ([]any, error) {
	values := make([]any, len(columns))
	for i := range columns {
		switch columns[i] {
		case series.FieldID, series.FieldName, series.FieldNameSort, series.FieldDescription:
			values[i] = new(sql.NullString)
		case series.FieldCreatedAt, series.FieldUpdatedAt:
			values[i] = new(sql.NullTime)
		default:
			values[i] = new(sql.UnknownType)
		}
	}
	return values, nil
}

// assignValues assigns the values that were returned from sql.Rows (after scanning)
// to the Series fields.
func (s *Series) assignValues(columns []string, values []any) error {
	if m, n := len(values), len(columns); m < n {
		return fmt.Errorf("mismatch number of scan values: %d != %d", m, n)
	}
	for i := range columns {
		switch columns[i] {
		case series.FieldID:
			if value, ok := values[i].(*sql.NullString); !ok {
				return fmt.Errorf("unexpected type %T for field id", values[i])
			} else if value.Valid {
				s.ID = value.String
			}
		case series.FieldName:
			if value, ok := values[i].(*sql.NullString); !ok {
				return fmt.Errorf("unexpected type %T for field name", values[i])
			} else if value.Valid {
				s.Name = value.String
			}
		case series.FieldNameSort:
			if value, ok := values[i].(*sql.NullString); !ok {
				return fmt.Errorf("unexpected type %T for field name_sort", values[i])
			} else if value.Valid {
				s.NameSort = value.String
			}
		case series.FieldDescription:
			if value, ok := values[i].(*sql.NullString); !ok {
				return fmt.Errorf("unexpected type %T for field description", values[i])
			} else if value.Valid {
				s.Description = value.String
			}
		case series.FieldCreatedAt:
			if value, ok := values[i].(*sql.NullTime); !ok {
				return fmt.Errorf("unexpected type %T for field created_at", values[i])
			} else if value.Valid {
				s.CreatedAt = value.Time
			}
		case series.FieldUpdatedAt:
			if value, ok := values[i].(*sql.NullTime); !ok {
				return fmt.Errorf("unexpected type %T for field updated_at", values[i])
			} else if value.Valid {
				s.UpdatedAt = value.Time
			}
		default:
			s.selectValues.Set(columns[i], values[i])
		}
	}
	return nil
}

// Value returns the ent.Value that was dynamically selected and assigned to the Series.
// This includes values selected through modifiers, order, etc.
func (s *Series) Value(name string) (ent.Value, error) {
	return s.selectValues.Get(name)
}

// QuerySeriesBooks queries the "series_books" edge of the Series entity.
func (s *Series) QuerySeriesBooks() *SeriesBookQuery {
	return NewSeriesClient(s.config).QuerySeriesBooks(s)
}

// Update returns a builder for updating this Series.
// Note that you need to call Series.Unwrap() before calling this method if this Series
// was returned from a transaction, and the transaction was committed or rolled back.
func (s *Series) Update() *SeriesUpdateOne {
	return NewSeriesClient(s.config).UpdateOne(s)
}

// Unwrap unwraps the Series entity that was returned from a transaction after it was closed,
// so that all future queries will be executed through the driver which created the transaction.
func (s *Series) Unwrap() *Series {
	_tx, ok := s.config.driver.(*txDriver)
	if !ok {
		panic("ent: Series is not a transactional entity")
	}
	s.config.driver = _tx.drv
	return s
}

// String implements the fmt.Stringer.
func (s *Series) String() string {
	var builder strings.Builder
	builder.WriteString("Series(")
	builder.WriteString(fmt.Sprintf("id=%v, ", s.ID))
	builder.WriteString("name=")
	builder.WriteString(s.Name)
	builder.WriteString(", ")
	builder.WriteString("name_sort=")
	builder.WriteString(s.NameSort)
	builder.WriteString(", ")
	builder.WriteString("description=")
	builder.WriteString(s.Description)
	builder.WriteString(", ")
	builder.WriteString("created_at=")
	builder.WriteString(s.CreatedAt.Format(time.ANSIC))
	builder.WriteString(", ")
	builder.WriteString("updated_at=")
	builder.WriteString(s.UpdatedAt.Format(time.ANSIC))
	builder.WriteByte(')')
	return builder.String()
}

// SeriesSlice is a parsable slice of Series.
type SeriesSlice []*Series