// Code generated by ent, DO NOT EDIT.

package ent

import (
	"fmt"
	"strings"

	"entgo.io/ent"
	"entgo.io/ent/dialect/sql"
	"github.com/ListenUpApp/ListenUp/internal/ent/library"
)

// Library is the model entity for the Library schema.
type Library struct {
	config `json:"-"`
	// ID of the ent.
	// Unique identifier for the library
	ID string `json:"id,omitempty"`
	// The libraries name
	Name string `json:"name,omitempty"`
	// Edges holds the relations/edges for other nodes in the graph.
	// The values are being populated by the LibraryQuery when eager-loading is set.
	Edges        LibraryEdges `json:"edges"`
	selectValues sql.SelectValues
}

// LibraryEdges holds the relations/edges for other nodes in the graph.
type LibraryEdges struct {
	// Users holds the value of the users edge.
	Users []*User `json:"users,omitempty"`
	// ActiveUsers holds the value of the active_users edge.
	ActiveUsers []*User `json:"active_users,omitempty"`
	// Folders holds the value of the folders edge.
	Folders []*Folder `json:"folders,omitempty"`
	// LibraryBooks holds the value of the library_books edge.
	LibraryBooks []*Book `json:"library_books,omitempty"`
	// loadedTypes holds the information for reporting if a
	// type was loaded (or requested) in eager-loading or not.
	loadedTypes [4]bool
}

// UsersOrErr returns the Users value or an error if the edge
// was not loaded in eager-loading.
func (e LibraryEdges) UsersOrErr() ([]*User, error) {
	if e.loadedTypes[0] {
		return e.Users, nil
	}
	return nil, &NotLoadedError{edge: "users"}
}

// ActiveUsersOrErr returns the ActiveUsers value or an error if the edge
// was not loaded in eager-loading.
func (e LibraryEdges) ActiveUsersOrErr() ([]*User, error) {
	if e.loadedTypes[1] {
		return e.ActiveUsers, nil
	}
	return nil, &NotLoadedError{edge: "active_users"}
}

// FoldersOrErr returns the Folders value or an error if the edge
// was not loaded in eager-loading.
func (e LibraryEdges) FoldersOrErr() ([]*Folder, error) {
	if e.loadedTypes[2] {
		return e.Folders, nil
	}
	return nil, &NotLoadedError{edge: "folders"}
}

// LibraryBooksOrErr returns the LibraryBooks value or an error if the edge
// was not loaded in eager-loading.
func (e LibraryEdges) LibraryBooksOrErr() ([]*Book, error) {
	if e.loadedTypes[3] {
		return e.LibraryBooks, nil
	}
	return nil, &NotLoadedError{edge: "library_books"}
}

// scanValues returns the types for scanning values from sql.Rows.
func (*Library) scanValues(columns []string) ([]any, error) {
	values := make([]any, len(columns))
	for i := range columns {
		switch columns[i] {
		case library.FieldID, library.FieldName:
			values[i] = new(sql.NullString)
		default:
			values[i] = new(sql.UnknownType)
		}
	}
	return values, nil
}

// assignValues assigns the values that were returned from sql.Rows (after scanning)
// to the Library fields.
func (l *Library) assignValues(columns []string, values []any) error {
	if m, n := len(values), len(columns); m < n {
		return fmt.Errorf("mismatch number of scan values: %d != %d", m, n)
	}
	for i := range columns {
		switch columns[i] {
		case library.FieldID:
			if value, ok := values[i].(*sql.NullString); !ok {
				return fmt.Errorf("unexpected type %T for field id", values[i])
			} else if value.Valid {
				l.ID = value.String
			}
		case library.FieldName:
			if value, ok := values[i].(*sql.NullString); !ok {
				return fmt.Errorf("unexpected type %T for field name", values[i])
			} else if value.Valid {
				l.Name = value.String
			}
		default:
			l.selectValues.Set(columns[i], values[i])
		}
	}
	return nil
}

// Value returns the ent.Value that was dynamically selected and assigned to the Library.
// This includes values selected through modifiers, order, etc.
func (l *Library) Value(name string) (ent.Value, error) {
	return l.selectValues.Get(name)
}

// QueryUsers queries the "users" edge of the Library entity.
func (l *Library) QueryUsers() *UserQuery {
	return NewLibraryClient(l.config).QueryUsers(l)
}

// QueryActiveUsers queries the "active_users" edge of the Library entity.
func (l *Library) QueryActiveUsers() *UserQuery {
	return NewLibraryClient(l.config).QueryActiveUsers(l)
}

// QueryFolders queries the "folders" edge of the Library entity.
func (l *Library) QueryFolders() *FolderQuery {
	return NewLibraryClient(l.config).QueryFolders(l)
}

// QueryLibraryBooks queries the "library_books" edge of the Library entity.
func (l *Library) QueryLibraryBooks() *BookQuery {
	return NewLibraryClient(l.config).QueryLibraryBooks(l)
}

// Update returns a builder for updating this Library.
// Note that you need to call Library.Unwrap() before calling this method if this Library
// was returned from a transaction, and the transaction was committed or rolled back.
func (l *Library) Update() *LibraryUpdateOne {
	return NewLibraryClient(l.config).UpdateOne(l)
}

// Unwrap unwraps the Library entity that was returned from a transaction after it was closed,
// so that all future queries will be executed through the driver which created the transaction.
func (l *Library) Unwrap() *Library {
	_tx, ok := l.config.driver.(*txDriver)
	if !ok {
		panic("ent: Library is not a transactional entity")
	}
	l.config.driver = _tx.drv
	return l
}

// String implements the fmt.Stringer.
func (l *Library) String() string {
	var builder strings.Builder
	builder.WriteString("Library(")
	builder.WriteString(fmt.Sprintf("id=%v, ", l.ID))
	builder.WriteString("name=")
	builder.WriteString(l.Name)
	builder.WriteByte(')')
	return builder.String()
}

// Libraries is a parsable slice of Library.
type Libraries []*Library
