// Code generated by ent, DO NOT EDIT.

package server

import (
	"time"

	"entgo.io/ent/dialect/sql"
	"entgo.io/ent/dialect/sql/sqlgraph"
)

const (
	// Label holds the string label denoting the server type in the database.
	Label = "server"
	// FieldID holds the string denoting the id field in the database.
	FieldID = "id"
	// FieldSetup holds the string denoting the setup field in the database.
	FieldSetup = "setup"
	// FieldCreatedAt holds the string denoting the created_at field in the database.
	FieldCreatedAt = "created_at"
	// FieldUpdatedAt holds the string denoting the updated_at field in the database.
	FieldUpdatedAt = "updated_at"
	// EdgeConfig holds the string denoting the config edge name in mutations.
	EdgeConfig = "config"
	// Table holds the table name of the server in the database.
	Table = "servers"
	// ConfigTable is the table that holds the config relation/edge.
	ConfigTable = "server_configs"
	// ConfigInverseTable is the table name for the ServerConfig entity.
	// It exists in this package in order to avoid circular dependency with the "serverconfig" package.
	ConfigInverseTable = "server_configs"
	// ConfigColumn is the table column denoting the config relation/edge.
	ConfigColumn = "server_config"
)

// Columns holds all SQL columns for server fields.
var Columns = []string{
	FieldID,
	FieldSetup,
	FieldCreatedAt,
	FieldUpdatedAt,
}

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
	// DefaultSetup holds the default value on creation for the "setup" field.
	DefaultSetup bool
	// DefaultCreatedAt holds the default value on creation for the "created_at" field.
	DefaultCreatedAt func() time.Time
	// DefaultUpdatedAt holds the default value on creation for the "updated_at" field.
	DefaultUpdatedAt func() time.Time
	// UpdateDefaultUpdatedAt holds the default value on update for the "updated_at" field.
	UpdateDefaultUpdatedAt func() time.Time
)

// OrderOption defines the ordering options for the Server queries.
type OrderOption func(*sql.Selector)

// ByID orders the results by the id field.
func ByID(opts ...sql.OrderTermOption) OrderOption {
	return sql.OrderByField(FieldID, opts...).ToFunc()
}

// BySetup orders the results by the setup field.
func BySetup(opts ...sql.OrderTermOption) OrderOption {
	return sql.OrderByField(FieldSetup, opts...).ToFunc()
}

// ByCreatedAt orders the results by the created_at field.
func ByCreatedAt(opts ...sql.OrderTermOption) OrderOption {
	return sql.OrderByField(FieldCreatedAt, opts...).ToFunc()
}

// ByUpdatedAt orders the results by the updated_at field.
func ByUpdatedAt(opts ...sql.OrderTermOption) OrderOption {
	return sql.OrderByField(FieldUpdatedAt, opts...).ToFunc()
}

// ByConfigField orders the results by config field.
func ByConfigField(field string, opts ...sql.OrderTermOption) OrderOption {
	return func(s *sql.Selector) {
		sqlgraph.OrderByNeighborTerms(s, newConfigStep(), sql.OrderByField(field, opts...))
	}
}
func newConfigStep() *sqlgraph.Step {
	return sqlgraph.NewStep(
		sqlgraph.From(Table, FieldID),
		sqlgraph.To(ConfigInverseTable, FieldID),
		sqlgraph.Edge(sqlgraph.O2O, false, ConfigTable, ConfigColumn),
	)
}
