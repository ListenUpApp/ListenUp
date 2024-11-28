// Code generated by ent, DO NOT EDIT.

package bookcover

import (
	"time"

	"entgo.io/ent/dialect/sql"
	"entgo.io/ent/dialect/sql/sqlgraph"
	"github.com/ListenUpApp/ListenUp/internal/ent/predicate"
)

// ID filters vertices based on their ID field.
func ID(id int) predicate.BookCover {
	return predicate.BookCover(sql.FieldEQ(FieldID, id))
}

// IDEQ applies the EQ predicate on the ID field.
func IDEQ(id int) predicate.BookCover {
	return predicate.BookCover(sql.FieldEQ(FieldID, id))
}

// IDNEQ applies the NEQ predicate on the ID field.
func IDNEQ(id int) predicate.BookCover {
	return predicate.BookCover(sql.FieldNEQ(FieldID, id))
}

// IDIn applies the In predicate on the ID field.
func IDIn(ids ...int) predicate.BookCover {
	return predicate.BookCover(sql.FieldIn(FieldID, ids...))
}

// IDNotIn applies the NotIn predicate on the ID field.
func IDNotIn(ids ...int) predicate.BookCover {
	return predicate.BookCover(sql.FieldNotIn(FieldID, ids...))
}

// IDGT applies the GT predicate on the ID field.
func IDGT(id int) predicate.BookCover {
	return predicate.BookCover(sql.FieldGT(FieldID, id))
}

// IDGTE applies the GTE predicate on the ID field.
func IDGTE(id int) predicate.BookCover {
	return predicate.BookCover(sql.FieldGTE(FieldID, id))
}

// IDLT applies the LT predicate on the ID field.
func IDLT(id int) predicate.BookCover {
	return predicate.BookCover(sql.FieldLT(FieldID, id))
}

// IDLTE applies the LTE predicate on the ID field.
func IDLTE(id int) predicate.BookCover {
	return predicate.BookCover(sql.FieldLTE(FieldID, id))
}

// Path applies equality check predicate on the "path" field. It's identical to PathEQ.
func Path(v string) predicate.BookCover {
	return predicate.BookCover(sql.FieldEQ(FieldPath, v))
}

// Format applies equality check predicate on the "format" field. It's identical to FormatEQ.
func Format(v string) predicate.BookCover {
	return predicate.BookCover(sql.FieldEQ(FieldFormat, v))
}

// Size applies equality check predicate on the "size" field. It's identical to SizeEQ.
func Size(v int64) predicate.BookCover {
	return predicate.BookCover(sql.FieldEQ(FieldSize, v))
}

// UpdatedAt applies equality check predicate on the "updated_at" field. It's identical to UpdatedAtEQ.
func UpdatedAt(v time.Time) predicate.BookCover {
	return predicate.BookCover(sql.FieldEQ(FieldUpdatedAt, v))
}

// PathEQ applies the EQ predicate on the "path" field.
func PathEQ(v string) predicate.BookCover {
	return predicate.BookCover(sql.FieldEQ(FieldPath, v))
}

// PathNEQ applies the NEQ predicate on the "path" field.
func PathNEQ(v string) predicate.BookCover {
	return predicate.BookCover(sql.FieldNEQ(FieldPath, v))
}

// PathIn applies the In predicate on the "path" field.
func PathIn(vs ...string) predicate.BookCover {
	return predicate.BookCover(sql.FieldIn(FieldPath, vs...))
}

// PathNotIn applies the NotIn predicate on the "path" field.
func PathNotIn(vs ...string) predicate.BookCover {
	return predicate.BookCover(sql.FieldNotIn(FieldPath, vs...))
}

// PathGT applies the GT predicate on the "path" field.
func PathGT(v string) predicate.BookCover {
	return predicate.BookCover(sql.FieldGT(FieldPath, v))
}

// PathGTE applies the GTE predicate on the "path" field.
func PathGTE(v string) predicate.BookCover {
	return predicate.BookCover(sql.FieldGTE(FieldPath, v))
}

// PathLT applies the LT predicate on the "path" field.
func PathLT(v string) predicate.BookCover {
	return predicate.BookCover(sql.FieldLT(FieldPath, v))
}

// PathLTE applies the LTE predicate on the "path" field.
func PathLTE(v string) predicate.BookCover {
	return predicate.BookCover(sql.FieldLTE(FieldPath, v))
}

// PathContains applies the Contains predicate on the "path" field.
func PathContains(v string) predicate.BookCover {
	return predicate.BookCover(sql.FieldContains(FieldPath, v))
}

// PathHasPrefix applies the HasPrefix predicate on the "path" field.
func PathHasPrefix(v string) predicate.BookCover {
	return predicate.BookCover(sql.FieldHasPrefix(FieldPath, v))
}

// PathHasSuffix applies the HasSuffix predicate on the "path" field.
func PathHasSuffix(v string) predicate.BookCover {
	return predicate.BookCover(sql.FieldHasSuffix(FieldPath, v))
}

// PathEqualFold applies the EqualFold predicate on the "path" field.
func PathEqualFold(v string) predicate.BookCover {
	return predicate.BookCover(sql.FieldEqualFold(FieldPath, v))
}

// PathContainsFold applies the ContainsFold predicate on the "path" field.
func PathContainsFold(v string) predicate.BookCover {
	return predicate.BookCover(sql.FieldContainsFold(FieldPath, v))
}

// FormatEQ applies the EQ predicate on the "format" field.
func FormatEQ(v string) predicate.BookCover {
	return predicate.BookCover(sql.FieldEQ(FieldFormat, v))
}

// FormatNEQ applies the NEQ predicate on the "format" field.
func FormatNEQ(v string) predicate.BookCover {
	return predicate.BookCover(sql.FieldNEQ(FieldFormat, v))
}

// FormatIn applies the In predicate on the "format" field.
func FormatIn(vs ...string) predicate.BookCover {
	return predicate.BookCover(sql.FieldIn(FieldFormat, vs...))
}

// FormatNotIn applies the NotIn predicate on the "format" field.
func FormatNotIn(vs ...string) predicate.BookCover {
	return predicate.BookCover(sql.FieldNotIn(FieldFormat, vs...))
}

// FormatGT applies the GT predicate on the "format" field.
func FormatGT(v string) predicate.BookCover {
	return predicate.BookCover(sql.FieldGT(FieldFormat, v))
}

// FormatGTE applies the GTE predicate on the "format" field.
func FormatGTE(v string) predicate.BookCover {
	return predicate.BookCover(sql.FieldGTE(FieldFormat, v))
}

// FormatLT applies the LT predicate on the "format" field.
func FormatLT(v string) predicate.BookCover {
	return predicate.BookCover(sql.FieldLT(FieldFormat, v))
}

// FormatLTE applies the LTE predicate on the "format" field.
func FormatLTE(v string) predicate.BookCover {
	return predicate.BookCover(sql.FieldLTE(FieldFormat, v))
}

// FormatContains applies the Contains predicate on the "format" field.
func FormatContains(v string) predicate.BookCover {
	return predicate.BookCover(sql.FieldContains(FieldFormat, v))
}

// FormatHasPrefix applies the HasPrefix predicate on the "format" field.
func FormatHasPrefix(v string) predicate.BookCover {
	return predicate.BookCover(sql.FieldHasPrefix(FieldFormat, v))
}

// FormatHasSuffix applies the HasSuffix predicate on the "format" field.
func FormatHasSuffix(v string) predicate.BookCover {
	return predicate.BookCover(sql.FieldHasSuffix(FieldFormat, v))
}

// FormatEqualFold applies the EqualFold predicate on the "format" field.
func FormatEqualFold(v string) predicate.BookCover {
	return predicate.BookCover(sql.FieldEqualFold(FieldFormat, v))
}

// FormatContainsFold applies the ContainsFold predicate on the "format" field.
func FormatContainsFold(v string) predicate.BookCover {
	return predicate.BookCover(sql.FieldContainsFold(FieldFormat, v))
}

// SizeEQ applies the EQ predicate on the "size" field.
func SizeEQ(v int64) predicate.BookCover {
	return predicate.BookCover(sql.FieldEQ(FieldSize, v))
}

// SizeNEQ applies the NEQ predicate on the "size" field.
func SizeNEQ(v int64) predicate.BookCover {
	return predicate.BookCover(sql.FieldNEQ(FieldSize, v))
}

// SizeIn applies the In predicate on the "size" field.
func SizeIn(vs ...int64) predicate.BookCover {
	return predicate.BookCover(sql.FieldIn(FieldSize, vs...))
}

// SizeNotIn applies the NotIn predicate on the "size" field.
func SizeNotIn(vs ...int64) predicate.BookCover {
	return predicate.BookCover(sql.FieldNotIn(FieldSize, vs...))
}

// SizeGT applies the GT predicate on the "size" field.
func SizeGT(v int64) predicate.BookCover {
	return predicate.BookCover(sql.FieldGT(FieldSize, v))
}

// SizeGTE applies the GTE predicate on the "size" field.
func SizeGTE(v int64) predicate.BookCover {
	return predicate.BookCover(sql.FieldGTE(FieldSize, v))
}

// SizeLT applies the LT predicate on the "size" field.
func SizeLT(v int64) predicate.BookCover {
	return predicate.BookCover(sql.FieldLT(FieldSize, v))
}

// SizeLTE applies the LTE predicate on the "size" field.
func SizeLTE(v int64) predicate.BookCover {
	return predicate.BookCover(sql.FieldLTE(FieldSize, v))
}

// UpdatedAtEQ applies the EQ predicate on the "updated_at" field.
func UpdatedAtEQ(v time.Time) predicate.BookCover {
	return predicate.BookCover(sql.FieldEQ(FieldUpdatedAt, v))
}

// UpdatedAtNEQ applies the NEQ predicate on the "updated_at" field.
func UpdatedAtNEQ(v time.Time) predicate.BookCover {
	return predicate.BookCover(sql.FieldNEQ(FieldUpdatedAt, v))
}

// UpdatedAtIn applies the In predicate on the "updated_at" field.
func UpdatedAtIn(vs ...time.Time) predicate.BookCover {
	return predicate.BookCover(sql.FieldIn(FieldUpdatedAt, vs...))
}

// UpdatedAtNotIn applies the NotIn predicate on the "updated_at" field.
func UpdatedAtNotIn(vs ...time.Time) predicate.BookCover {
	return predicate.BookCover(sql.FieldNotIn(FieldUpdatedAt, vs...))
}

// UpdatedAtGT applies the GT predicate on the "updated_at" field.
func UpdatedAtGT(v time.Time) predicate.BookCover {
	return predicate.BookCover(sql.FieldGT(FieldUpdatedAt, v))
}

// UpdatedAtGTE applies the GTE predicate on the "updated_at" field.
func UpdatedAtGTE(v time.Time) predicate.BookCover {
	return predicate.BookCover(sql.FieldGTE(FieldUpdatedAt, v))
}

// UpdatedAtLT applies the LT predicate on the "updated_at" field.
func UpdatedAtLT(v time.Time) predicate.BookCover {
	return predicate.BookCover(sql.FieldLT(FieldUpdatedAt, v))
}

// UpdatedAtLTE applies the LTE predicate on the "updated_at" field.
func UpdatedAtLTE(v time.Time) predicate.BookCover {
	return predicate.BookCover(sql.FieldLTE(FieldUpdatedAt, v))
}

// HasBook applies the HasEdge predicate on the "book" edge.
func HasBook() predicate.BookCover {
	return predicate.BookCover(func(s *sql.Selector) {
		step := sqlgraph.NewStep(
			sqlgraph.From(Table, FieldID),
			sqlgraph.Edge(sqlgraph.O2O, true, BookTable, BookColumn),
		)
		sqlgraph.HasNeighbors(s, step)
	})
}

// HasBookWith applies the HasEdge predicate on the "book" edge with a given conditions (other predicates).
func HasBookWith(preds ...predicate.Book) predicate.BookCover {
	return predicate.BookCover(func(s *sql.Selector) {
		step := newBookStep()
		sqlgraph.HasNeighborsWith(s, step, func(s *sql.Selector) {
			for _, p := range preds {
				p(s)
			}
		})
	})
}

// And groups predicates with the AND operator between them.
func And(predicates ...predicate.BookCover) predicate.BookCover {
	return predicate.BookCover(sql.AndPredicates(predicates...))
}

// Or groups predicates with the OR operator between them.
func Or(predicates ...predicate.BookCover) predicate.BookCover {
	return predicate.BookCover(sql.OrPredicates(predicates...))
}

// Not applies the not operator on the given predicate.
func Not(p predicate.BookCover) predicate.BookCover {
	return predicate.BookCover(sql.NotPredicates(p))
}