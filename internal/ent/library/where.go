// Code generated by ent, DO NOT EDIT.

package library

import (
	"entgo.io/ent/dialect/sql"
	"entgo.io/ent/dialect/sql/sqlgraph"
	"github.com/ListenUpApp/ListenUp/internal/ent/predicate"
)

// ID filters vertices based on their ID field.
func ID(id string) predicate.Library {
	return predicate.Library(sql.FieldEQ(FieldID, id))
}

// IDEQ applies the EQ predicate on the ID field.
func IDEQ(id string) predicate.Library {
	return predicate.Library(sql.FieldEQ(FieldID, id))
}

// IDNEQ applies the NEQ predicate on the ID field.
func IDNEQ(id string) predicate.Library {
	return predicate.Library(sql.FieldNEQ(FieldID, id))
}

// IDIn applies the In predicate on the ID field.
func IDIn(ids ...string) predicate.Library {
	return predicate.Library(sql.FieldIn(FieldID, ids...))
}

// IDNotIn applies the NotIn predicate on the ID field.
func IDNotIn(ids ...string) predicate.Library {
	return predicate.Library(sql.FieldNotIn(FieldID, ids...))
}

// IDGT applies the GT predicate on the ID field.
func IDGT(id string) predicate.Library {
	return predicate.Library(sql.FieldGT(FieldID, id))
}

// IDGTE applies the GTE predicate on the ID field.
func IDGTE(id string) predicate.Library {
	return predicate.Library(sql.FieldGTE(FieldID, id))
}

// IDLT applies the LT predicate on the ID field.
func IDLT(id string) predicate.Library {
	return predicate.Library(sql.FieldLT(FieldID, id))
}

// IDLTE applies the LTE predicate on the ID field.
func IDLTE(id string) predicate.Library {
	return predicate.Library(sql.FieldLTE(FieldID, id))
}

// IDEqualFold applies the EqualFold predicate on the ID field.
func IDEqualFold(id string) predicate.Library {
	return predicate.Library(sql.FieldEqualFold(FieldID, id))
}

// IDContainsFold applies the ContainsFold predicate on the ID field.
func IDContainsFold(id string) predicate.Library {
	return predicate.Library(sql.FieldContainsFold(FieldID, id))
}

// Name applies equality check predicate on the "name" field. It's identical to NameEQ.
func Name(v string) predicate.Library {
	return predicate.Library(sql.FieldEQ(FieldName, v))
}

// NameEQ applies the EQ predicate on the "name" field.
func NameEQ(v string) predicate.Library {
	return predicate.Library(sql.FieldEQ(FieldName, v))
}

// NameNEQ applies the NEQ predicate on the "name" field.
func NameNEQ(v string) predicate.Library {
	return predicate.Library(sql.FieldNEQ(FieldName, v))
}

// NameIn applies the In predicate on the "name" field.
func NameIn(vs ...string) predicate.Library {
	return predicate.Library(sql.FieldIn(FieldName, vs...))
}

// NameNotIn applies the NotIn predicate on the "name" field.
func NameNotIn(vs ...string) predicate.Library {
	return predicate.Library(sql.FieldNotIn(FieldName, vs...))
}

// NameGT applies the GT predicate on the "name" field.
func NameGT(v string) predicate.Library {
	return predicate.Library(sql.FieldGT(FieldName, v))
}

// NameGTE applies the GTE predicate on the "name" field.
func NameGTE(v string) predicate.Library {
	return predicate.Library(sql.FieldGTE(FieldName, v))
}

// NameLT applies the LT predicate on the "name" field.
func NameLT(v string) predicate.Library {
	return predicate.Library(sql.FieldLT(FieldName, v))
}

// NameLTE applies the LTE predicate on the "name" field.
func NameLTE(v string) predicate.Library {
	return predicate.Library(sql.FieldLTE(FieldName, v))
}

// NameContains applies the Contains predicate on the "name" field.
func NameContains(v string) predicate.Library {
	return predicate.Library(sql.FieldContains(FieldName, v))
}

// NameHasPrefix applies the HasPrefix predicate on the "name" field.
func NameHasPrefix(v string) predicate.Library {
	return predicate.Library(sql.FieldHasPrefix(FieldName, v))
}

// NameHasSuffix applies the HasSuffix predicate on the "name" field.
func NameHasSuffix(v string) predicate.Library {
	return predicate.Library(sql.FieldHasSuffix(FieldName, v))
}

// NameEqualFold applies the EqualFold predicate on the "name" field.
func NameEqualFold(v string) predicate.Library {
	return predicate.Library(sql.FieldEqualFold(FieldName, v))
}

// NameContainsFold applies the ContainsFold predicate on the "name" field.
func NameContainsFold(v string) predicate.Library {
	return predicate.Library(sql.FieldContainsFold(FieldName, v))
}

// HasUsers applies the HasEdge predicate on the "users" edge.
func HasUsers() predicate.Library {
	return predicate.Library(func(s *sql.Selector) {
		step := sqlgraph.NewStep(
			sqlgraph.From(Table, FieldID),
			sqlgraph.Edge(sqlgraph.M2M, true, UsersTable, UsersPrimaryKey...),
		)
		sqlgraph.HasNeighbors(s, step)
	})
}

// HasUsersWith applies the HasEdge predicate on the "users" edge with a given conditions (other predicates).
func HasUsersWith(preds ...predicate.User) predicate.Library {
	return predicate.Library(func(s *sql.Selector) {
		step := newUsersStep()
		sqlgraph.HasNeighborsWith(s, step, func(s *sql.Selector) {
			for _, p := range preds {
				p(s)
			}
		})
	})
}

// HasActiveUsers applies the HasEdge predicate on the "active_users" edge.
func HasActiveUsers() predicate.Library {
	return predicate.Library(func(s *sql.Selector) {
		step := sqlgraph.NewStep(
			sqlgraph.From(Table, FieldID),
			sqlgraph.Edge(sqlgraph.O2M, true, ActiveUsersTable, ActiveUsersColumn),
		)
		sqlgraph.HasNeighbors(s, step)
	})
}

// HasActiveUsersWith applies the HasEdge predicate on the "active_users" edge with a given conditions (other predicates).
func HasActiveUsersWith(preds ...predicate.User) predicate.Library {
	return predicate.Library(func(s *sql.Selector) {
		step := newActiveUsersStep()
		sqlgraph.HasNeighborsWith(s, step, func(s *sql.Selector) {
			for _, p := range preds {
				p(s)
			}
		})
	})
}

// HasFolders applies the HasEdge predicate on the "folders" edge.
func HasFolders() predicate.Library {
	return predicate.Library(func(s *sql.Selector) {
		step := sqlgraph.NewStep(
			sqlgraph.From(Table, FieldID),
			sqlgraph.Edge(sqlgraph.M2M, false, FoldersTable, FoldersPrimaryKey...),
		)
		sqlgraph.HasNeighbors(s, step)
	})
}

// HasFoldersWith applies the HasEdge predicate on the "folders" edge with a given conditions (other predicates).
func HasFoldersWith(preds ...predicate.Folder) predicate.Library {
	return predicate.Library(func(s *sql.Selector) {
		step := newFoldersStep()
		sqlgraph.HasNeighborsWith(s, step, func(s *sql.Selector) {
			for _, p := range preds {
				p(s)
			}
		})
	})
}

// HasLibraryBooks applies the HasEdge predicate on the "library_books" edge.
func HasLibraryBooks() predicate.Library {
	return predicate.Library(func(s *sql.Selector) {
		step := sqlgraph.NewStep(
			sqlgraph.From(Table, FieldID),
			sqlgraph.Edge(sqlgraph.O2M, false, LibraryBooksTable, LibraryBooksColumn),
		)
		sqlgraph.HasNeighbors(s, step)
	})
}

// HasLibraryBooksWith applies the HasEdge predicate on the "library_books" edge with a given conditions (other predicates).
func HasLibraryBooksWith(preds ...predicate.Book) predicate.Library {
	return predicate.Library(func(s *sql.Selector) {
		step := newLibraryBooksStep()
		sqlgraph.HasNeighborsWith(s, step, func(s *sql.Selector) {
			for _, p := range preds {
				p(s)
			}
		})
	})
}

// And groups predicates with the AND operator between them.
func And(predicates ...predicate.Library) predicate.Library {
	return predicate.Library(sql.AndPredicates(predicates...))
}

// Or groups predicates with the OR operator between them.
func Or(predicates ...predicate.Library) predicate.Library {
	return predicate.Library(sql.OrPredicates(predicates...))
}

// Not applies the not operator on the given predicate.
func Not(p predicate.Library) predicate.Library {
	return predicate.Library(sql.NotPredicates(p))
}
