// Code generated by ent, DO NOT EDIT.

package ent

import (
	"fmt"
	"strings"
	"time"

	"entgo.io/ent"
	"entgo.io/ent/dialect/sql"
	"github.com/ListenUpApp/ListenUp/internal/ent/narrator"
)

// Narrator is the model entity for the Narrator schema.
type Narrator struct {
	config `json:"-"`
	// ID of the ent.
	ID string `json:"id,omitempty"`
	// Name holds the value of the "name" field.
	Name string `json:"name,omitempty"`
	// NameSort holds the value of the "name_sort" field.
	NameSort string `json:"name_sort,omitempty"`
	// Description holds the value of the "description" field.
	Description string `json:"description,omitempty"`
	// ImagePath holds the value of the "image_path" field.
	ImagePath string `json:"image_path,omitempty"`
	// CreatedAt holds the value of the "created_at" field.
	CreatedAt time.Time `json:"created_at,omitempty"`
	// UpdatedAt holds the value of the "updated_at" field.
	UpdatedAt time.Time `json:"updated_at,omitempty"`
	// Edges holds the relations/edges for other nodes in the graph.
	// The values are being populated by the NarratorQuery when eager-loading is set.
	Edges        NarratorEdges `json:"edges"`
	selectValues sql.SelectValues
}

// NarratorEdges holds the relations/edges for other nodes in the graph.
type NarratorEdges struct {
	// Books holds the value of the books edge.
	Books []*Book `json:"books,omitempty"`
	// loadedTypes holds the information for reporting if a
	// type was loaded (or requested) in eager-loading or not.
	loadedTypes [1]bool
}

// BooksOrErr returns the Books value or an error if the edge
// was not loaded in eager-loading.
func (e NarratorEdges) BooksOrErr() ([]*Book, error) {
	if e.loadedTypes[0] {
		return e.Books, nil
	}
	return nil, &NotLoadedError{edge: "books"}
}

// scanValues returns the types for scanning values from sql.Rows.
func (*Narrator) scanValues(columns []string) ([]any, error) {
	values := make([]any, len(columns))
	for i := range columns {
		switch columns[i] {
		case narrator.FieldID, narrator.FieldName, narrator.FieldNameSort, narrator.FieldDescription, narrator.FieldImagePath:
			values[i] = new(sql.NullString)
		case narrator.FieldCreatedAt, narrator.FieldUpdatedAt:
			values[i] = new(sql.NullTime)
		default:
			values[i] = new(sql.UnknownType)
		}
	}
	return values, nil
}

// assignValues assigns the values that were returned from sql.Rows (after scanning)
// to the Narrator fields.
func (n *Narrator) assignValues(columns []string, values []any) error {
	if m, n := len(values), len(columns); m < n {
		return fmt.Errorf("mismatch number of scan values: %d != %d", m, n)
	}
	for i := range columns {
		switch columns[i] {
		case narrator.FieldID:
			if value, ok := values[i].(*sql.NullString); !ok {
				return fmt.Errorf("unexpected type %T for field id", values[i])
			} else if value.Valid {
				n.ID = value.String
			}
		case narrator.FieldName:
			if value, ok := values[i].(*sql.NullString); !ok {
				return fmt.Errorf("unexpected type %T for field name", values[i])
			} else if value.Valid {
				n.Name = value.String
			}
		case narrator.FieldNameSort:
			if value, ok := values[i].(*sql.NullString); !ok {
				return fmt.Errorf("unexpected type %T for field name_sort", values[i])
			} else if value.Valid {
				n.NameSort = value.String
			}
		case narrator.FieldDescription:
			if value, ok := values[i].(*sql.NullString); !ok {
				return fmt.Errorf("unexpected type %T for field description", values[i])
			} else if value.Valid {
				n.Description = value.String
			}
		case narrator.FieldImagePath:
			if value, ok := values[i].(*sql.NullString); !ok {
				return fmt.Errorf("unexpected type %T for field image_path", values[i])
			} else if value.Valid {
				n.ImagePath = value.String
			}
		case narrator.FieldCreatedAt:
			if value, ok := values[i].(*sql.NullTime); !ok {
				return fmt.Errorf("unexpected type %T for field created_at", values[i])
			} else if value.Valid {
				n.CreatedAt = value.Time
			}
		case narrator.FieldUpdatedAt:
			if value, ok := values[i].(*sql.NullTime); !ok {
				return fmt.Errorf("unexpected type %T for field updated_at", values[i])
			} else if value.Valid {
				n.UpdatedAt = value.Time
			}
		default:
			n.selectValues.Set(columns[i], values[i])
		}
	}
	return nil
}

// Value returns the ent.Value that was dynamically selected and assigned to the Narrator.
// This includes values selected through modifiers, order, etc.
func (n *Narrator) Value(name string) (ent.Value, error) {
	return n.selectValues.Get(name)
}

// QueryBooks queries the "books" edge of the Narrator entity.
func (n *Narrator) QueryBooks() *BookQuery {
	return NewNarratorClient(n.config).QueryBooks(n)
}

// Update returns a builder for updating this Narrator.
// Note that you need to call Narrator.Unwrap() before calling this method if this Narrator
// was returned from a transaction, and the transaction was committed or rolled back.
func (n *Narrator) Update() *NarratorUpdateOne {
	return NewNarratorClient(n.config).UpdateOne(n)
}

// Unwrap unwraps the Narrator entity that was returned from a transaction after it was closed,
// so that all future queries will be executed through the driver which created the transaction.
func (n *Narrator) Unwrap() *Narrator {
	_tx, ok := n.config.driver.(*txDriver)
	if !ok {
		panic("ent: Narrator is not a transactional entity")
	}
	n.config.driver = _tx.drv
	return n
}

// String implements the fmt.Stringer.
func (n *Narrator) String() string {
	var builder strings.Builder
	builder.WriteString("Narrator(")
	builder.WriteString(fmt.Sprintf("id=%v, ", n.ID))
	builder.WriteString("name=")
	builder.WriteString(n.Name)
	builder.WriteString(", ")
	builder.WriteString("name_sort=")
	builder.WriteString(n.NameSort)
	builder.WriteString(", ")
	builder.WriteString("description=")
	builder.WriteString(n.Description)
	builder.WriteString(", ")
	builder.WriteString("image_path=")
	builder.WriteString(n.ImagePath)
	builder.WriteString(", ")
	builder.WriteString("created_at=")
	builder.WriteString(n.CreatedAt.Format(time.ANSIC))
	builder.WriteString(", ")
	builder.WriteString("updated_at=")
	builder.WriteString(n.UpdatedAt.Format(time.ANSIC))
	builder.WriteByte(')')
	return builder.String()
}

// Narrators is a parsable slice of Narrator.
type Narrators []*Narrator
