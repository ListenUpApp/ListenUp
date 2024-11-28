// Code generated by ent, DO NOT EDIT.

package chapter

import (
	"entgo.io/ent/dialect/sql"
	"entgo.io/ent/dialect/sql/sqlgraph"
)

const (
	// Label holds the string label denoting the chapter type in the database.
	Label = "chapter"
	// FieldID holds the string denoting the id field in the database.
	FieldID = "id"
	// FieldIndex holds the string denoting the index field in the database.
	FieldIndex = "index"
	// FieldTitle holds the string denoting the title field in the database.
	FieldTitle = "title"
	// FieldStart holds the string denoting the start field in the database.
	FieldStart = "start"
	// FieldEnd holds the string denoting the end field in the database.
	FieldEnd = "end"
	// EdgeBook holds the string denoting the book edge name in mutations.
	EdgeBook = "book"
	// Table holds the table name of the chapter in the database.
	Table = "chapters"
	// BookTable is the table that holds the book relation/edge.
	BookTable = "chapters"
	// BookInverseTable is the table name for the Book entity.
	// It exists in this package in order to avoid circular dependency with the "book" package.
	BookInverseTable = "books"
	// BookColumn is the table column denoting the book relation/edge.
	BookColumn = "book_chapters"
)

// Columns holds all SQL columns for chapter fields.
var Columns = []string{
	FieldID,
	FieldIndex,
	FieldTitle,
	FieldStart,
	FieldEnd,
}

// ForeignKeys holds the SQL foreign-keys that are owned by the "chapters"
// table and are not defined as standalone fields in the schema.
var ForeignKeys = []string{
	"book_chapters",
}

// ValidColumn reports if the column name is valid (part of the table columns).
func ValidColumn(column string) bool {
	for i := range Columns {
		if column == Columns[i] {
			return true
		}
	}
	for i := range ForeignKeys {
		if column == ForeignKeys[i] {
			return true
		}
	}
	return false
}

var (
	// IndexValidator is a validator for the "index" field. It is called by the builders before save.
	IndexValidator func(int) error
	// TitleValidator is a validator for the "title" field. It is called by the builders before save.
	TitleValidator func(string) error
	// StartValidator is a validator for the "start" field. It is called by the builders before save.
	StartValidator func(float64) error
	// EndValidator is a validator for the "end" field. It is called by the builders before save.
	EndValidator func(float64) error
)

// OrderOption defines the ordering options for the Chapter queries.
type OrderOption func(*sql.Selector)

// ByID orders the results by the id field.
func ByID(opts ...sql.OrderTermOption) OrderOption {
	return sql.OrderByField(FieldID, opts...).ToFunc()
}

// ByIndex orders the results by the index field.
func ByIndex(opts ...sql.OrderTermOption) OrderOption {
	return sql.OrderByField(FieldIndex, opts...).ToFunc()
}

// ByTitle orders the results by the title field.
func ByTitle(opts ...sql.OrderTermOption) OrderOption {
	return sql.OrderByField(FieldTitle, opts...).ToFunc()
}

// ByStart orders the results by the start field.
func ByStart(opts ...sql.OrderTermOption) OrderOption {
	return sql.OrderByField(FieldStart, opts...).ToFunc()
}

// ByEnd orders the results by the end field.
func ByEnd(opts ...sql.OrderTermOption) OrderOption {
	return sql.OrderByField(FieldEnd, opts...).ToFunc()
}

// ByBookField orders the results by book field.
func ByBookField(field string, opts ...sql.OrderTermOption) OrderOption {
	return func(s *sql.Selector) {
		sqlgraph.OrderByNeighborTerms(s, newBookStep(), sql.OrderByField(field, opts...))
	}
}
func newBookStep() *sqlgraph.Step {
	return sqlgraph.NewStep(
		sqlgraph.From(Table, FieldID),
		sqlgraph.To(BookInverseTable, FieldID),
		sqlgraph.Edge(sqlgraph.M2O, true, BookTable, BookColumn),
	)
}
