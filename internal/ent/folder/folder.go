// Code generated by ent, DO NOT EDIT.

package folder

import (
	"entgo.io/ent/dialect/sql"
	"entgo.io/ent/dialect/sql/sqlgraph"
)

const (
	// Label holds the string label denoting the folder type in the database.
	Label = "folder"
	// FieldID holds the string denoting the id field in the database.
	FieldID = "id"
	// FieldName holds the string denoting the name field in the database.
	FieldName = "name"
	// FieldPath holds the string denoting the path field in the database.
	FieldPath = "path"
	// FieldLastScannedAt holds the string denoting the last_scanned_at field in the database.
	FieldLastScannedAt = "last_scanned_at"
	// EdgeLibraries holds the string denoting the libraries edge name in mutations.
	EdgeLibraries = "libraries"
	// EdgeBooks holds the string denoting the books edge name in mutations.
	EdgeBooks = "books"
	// Table holds the table name of the folder in the database.
	Table = "folders"
	// LibrariesTable is the table that holds the libraries relation/edge. The primary key declared below.
	LibrariesTable = "library_folders"
	// LibrariesInverseTable is the table name for the Library entity.
	// It exists in this package in order to avoid circular dependency with the "library" package.
	LibrariesInverseTable = "libraries"
	// BooksTable is the table that holds the books relation/edge.
	BooksTable = "books"
	// BooksInverseTable is the table name for the Book entity.
	// It exists in this package in order to avoid circular dependency with the "book" package.
	BooksInverseTable = "books"
	// BooksColumn is the table column denoting the books relation/edge.
	BooksColumn = "folder_books"
)

// Columns holds all SQL columns for folder fields.
var Columns = []string{
	FieldID,
	FieldName,
	FieldPath,
	FieldLastScannedAt,
}

var (
	// LibrariesPrimaryKey and LibrariesColumn2 are the table columns denoting the
	// primary key for the libraries relation (M2M).
	LibrariesPrimaryKey = []string{"library_id", "folder_id"}
)

// ValidColumn reports if the column name is valid (part of the table columns).
func ValidColumn(column string) bool {
	for i := range Columns {
		if column == Columns[i] {
			return true
		}
	}
	return false
}

var (
	// DefaultID holds the default value on creation for the "id" field.
	DefaultID func() string
)

// OrderOption defines the ordering options for the Folder queries.
type OrderOption func(*sql.Selector)

// ByID orders the results by the id field.
func ByID(opts ...sql.OrderTermOption) OrderOption {
	return sql.OrderByField(FieldID, opts...).ToFunc()
}

// ByName orders the results by the name field.
func ByName(opts ...sql.OrderTermOption) OrderOption {
	return sql.OrderByField(FieldName, opts...).ToFunc()
}

// ByPath orders the results by the path field.
func ByPath(opts ...sql.OrderTermOption) OrderOption {
	return sql.OrderByField(FieldPath, opts...).ToFunc()
}

// ByLastScannedAt orders the results by the last_scanned_at field.
func ByLastScannedAt(opts ...sql.OrderTermOption) OrderOption {
	return sql.OrderByField(FieldLastScannedAt, opts...).ToFunc()
}

// ByLibrariesCount orders the results by libraries count.
func ByLibrariesCount(opts ...sql.OrderTermOption) OrderOption {
	return func(s *sql.Selector) {
		sqlgraph.OrderByNeighborsCount(s, newLibrariesStep(), opts...)
	}
}

// ByLibraries orders the results by libraries terms.
func ByLibraries(term sql.OrderTerm, terms ...sql.OrderTerm) OrderOption {
	return func(s *sql.Selector) {
		sqlgraph.OrderByNeighborTerms(s, newLibrariesStep(), append([]sql.OrderTerm{term}, terms...)...)
	}
}

// ByBooksCount orders the results by books count.
func ByBooksCount(opts ...sql.OrderTermOption) OrderOption {
	return func(s *sql.Selector) {
		sqlgraph.OrderByNeighborsCount(s, newBooksStep(), opts...)
	}
}

// ByBooks orders the results by books terms.
func ByBooks(term sql.OrderTerm, terms ...sql.OrderTerm) OrderOption {
	return func(s *sql.Selector) {
		sqlgraph.OrderByNeighborTerms(s, newBooksStep(), append([]sql.OrderTerm{term}, terms...)...)
	}
}
func newLibrariesStep() *sqlgraph.Step {
	return sqlgraph.NewStep(
		sqlgraph.From(Table, FieldID),
		sqlgraph.To(LibrariesInverseTable, FieldID),
		sqlgraph.Edge(sqlgraph.M2M, true, LibrariesTable, LibrariesPrimaryKey...),
	)
}
func newBooksStep() *sqlgraph.Step {
	return sqlgraph.NewStep(
		sqlgraph.From(Table, FieldID),
		sqlgraph.To(BooksInverseTable, FieldID),
		sqlgraph.Edge(sqlgraph.O2M, false, BooksTable, BooksColumn),
	)
}
