// Code generated by ent, DO NOT EDIT.

package coverversion

import (
	"time"

	"entgo.io/ent/dialect/sql"
	"entgo.io/ent/dialect/sql/sqlgraph"
	"github.com/ListenUpApp/ListenUp/internal/ent/predicate"
)

// ID filters vertices based on their ID field.
func ID(id int) predicate.CoverVersion {
	return predicate.CoverVersion(sql.FieldEQ(FieldID, id))
}

// IDEQ applies the EQ predicate on the ID field.
func IDEQ(id int) predicate.CoverVersion {
	return predicate.CoverVersion(sql.FieldEQ(FieldID, id))
}

// IDNEQ applies the NEQ predicate on the ID field.
func IDNEQ(id int) predicate.CoverVersion {
	return predicate.CoverVersion(sql.FieldNEQ(FieldID, id))
}

// IDIn applies the In predicate on the ID field.
func IDIn(ids ...int) predicate.CoverVersion {
	return predicate.CoverVersion(sql.FieldIn(FieldID, ids...))
}

// IDNotIn applies the NotIn predicate on the ID field.
func IDNotIn(ids ...int) predicate.CoverVersion {
	return predicate.CoverVersion(sql.FieldNotIn(FieldID, ids...))
}

// IDGT applies the GT predicate on the ID field.
func IDGT(id int) predicate.CoverVersion {
	return predicate.CoverVersion(sql.FieldGT(FieldID, id))
}

// IDGTE applies the GTE predicate on the ID field.
func IDGTE(id int) predicate.CoverVersion {
	return predicate.CoverVersion(sql.FieldGTE(FieldID, id))
}

// IDLT applies the LT predicate on the ID field.
func IDLT(id int) predicate.CoverVersion {
	return predicate.CoverVersion(sql.FieldLT(FieldID, id))
}

// IDLTE applies the LTE predicate on the ID field.
func IDLTE(id int) predicate.CoverVersion {
	return predicate.CoverVersion(sql.FieldLTE(FieldID, id))
}

// Path applies equality check predicate on the "path" field. It's identical to PathEQ.
func Path(v string) predicate.CoverVersion {
	return predicate.CoverVersion(sql.FieldEQ(FieldPath, v))
}

// Format applies equality check predicate on the "format" field. It's identical to FormatEQ.
func Format(v string) predicate.CoverVersion {
	return predicate.CoverVersion(sql.FieldEQ(FieldFormat, v))
}

// Size applies equality check predicate on the "size" field. It's identical to SizeEQ.
func Size(v int64) predicate.CoverVersion {
	return predicate.CoverVersion(sql.FieldEQ(FieldSize, v))
}

// Suffix applies equality check predicate on the "suffix" field. It's identical to SuffixEQ.
func Suffix(v string) predicate.CoverVersion {
	return predicate.CoverVersion(sql.FieldEQ(FieldSuffix, v))
}

// UpdatedAt applies equality check predicate on the "updated_at" field. It's identical to UpdatedAtEQ.
func UpdatedAt(v time.Time) predicate.CoverVersion {
	return predicate.CoverVersion(sql.FieldEQ(FieldUpdatedAt, v))
}

// PathEQ applies the EQ predicate on the "path" field.
func PathEQ(v string) predicate.CoverVersion {
	return predicate.CoverVersion(sql.FieldEQ(FieldPath, v))
}

// PathNEQ applies the NEQ predicate on the "path" field.
func PathNEQ(v string) predicate.CoverVersion {
	return predicate.CoverVersion(sql.FieldNEQ(FieldPath, v))
}

// PathIn applies the In predicate on the "path" field.
func PathIn(vs ...string) predicate.CoverVersion {
	return predicate.CoverVersion(sql.FieldIn(FieldPath, vs...))
}

// PathNotIn applies the NotIn predicate on the "path" field.
func PathNotIn(vs ...string) predicate.CoverVersion {
	return predicate.CoverVersion(sql.FieldNotIn(FieldPath, vs...))
}

// PathGT applies the GT predicate on the "path" field.
func PathGT(v string) predicate.CoverVersion {
	return predicate.CoverVersion(sql.FieldGT(FieldPath, v))
}

// PathGTE applies the GTE predicate on the "path" field.
func PathGTE(v string) predicate.CoverVersion {
	return predicate.CoverVersion(sql.FieldGTE(FieldPath, v))
}

// PathLT applies the LT predicate on the "path" field.
func PathLT(v string) predicate.CoverVersion {
	return predicate.CoverVersion(sql.FieldLT(FieldPath, v))
}

// PathLTE applies the LTE predicate on the "path" field.
func PathLTE(v string) predicate.CoverVersion {
	return predicate.CoverVersion(sql.FieldLTE(FieldPath, v))
}

// PathContains applies the Contains predicate on the "path" field.
func PathContains(v string) predicate.CoverVersion {
	return predicate.CoverVersion(sql.FieldContains(FieldPath, v))
}

// PathHasPrefix applies the HasPrefix predicate on the "path" field.
func PathHasPrefix(v string) predicate.CoverVersion {
	return predicate.CoverVersion(sql.FieldHasPrefix(FieldPath, v))
}

// PathHasSuffix applies the HasSuffix predicate on the "path" field.
func PathHasSuffix(v string) predicate.CoverVersion {
	return predicate.CoverVersion(sql.FieldHasSuffix(FieldPath, v))
}

// PathEqualFold applies the EqualFold predicate on the "path" field.
func PathEqualFold(v string) predicate.CoverVersion {
	return predicate.CoverVersion(sql.FieldEqualFold(FieldPath, v))
}

// PathContainsFold applies the ContainsFold predicate on the "path" field.
func PathContainsFold(v string) predicate.CoverVersion {
	return predicate.CoverVersion(sql.FieldContainsFold(FieldPath, v))
}

// FormatEQ applies the EQ predicate on the "format" field.
func FormatEQ(v string) predicate.CoverVersion {
	return predicate.CoverVersion(sql.FieldEQ(FieldFormat, v))
}

// FormatNEQ applies the NEQ predicate on the "format" field.
func FormatNEQ(v string) predicate.CoverVersion {
	return predicate.CoverVersion(sql.FieldNEQ(FieldFormat, v))
}

// FormatIn applies the In predicate on the "format" field.
func FormatIn(vs ...string) predicate.CoverVersion {
	return predicate.CoverVersion(sql.FieldIn(FieldFormat, vs...))
}

// FormatNotIn applies the NotIn predicate on the "format" field.
func FormatNotIn(vs ...string) predicate.CoverVersion {
	return predicate.CoverVersion(sql.FieldNotIn(FieldFormat, vs...))
}

// FormatGT applies the GT predicate on the "format" field.
func FormatGT(v string) predicate.CoverVersion {
	return predicate.CoverVersion(sql.FieldGT(FieldFormat, v))
}

// FormatGTE applies the GTE predicate on the "format" field.
func FormatGTE(v string) predicate.CoverVersion {
	return predicate.CoverVersion(sql.FieldGTE(FieldFormat, v))
}

// FormatLT applies the LT predicate on the "format" field.
func FormatLT(v string) predicate.CoverVersion {
	return predicate.CoverVersion(sql.FieldLT(FieldFormat, v))
}

// FormatLTE applies the LTE predicate on the "format" field.
func FormatLTE(v string) predicate.CoverVersion {
	return predicate.CoverVersion(sql.FieldLTE(FieldFormat, v))
}

// FormatContains applies the Contains predicate on the "format" field.
func FormatContains(v string) predicate.CoverVersion {
	return predicate.CoverVersion(sql.FieldContains(FieldFormat, v))
}

// FormatHasPrefix applies the HasPrefix predicate on the "format" field.
func FormatHasPrefix(v string) predicate.CoverVersion {
	return predicate.CoverVersion(sql.FieldHasPrefix(FieldFormat, v))
}

// FormatHasSuffix applies the HasSuffix predicate on the "format" field.
func FormatHasSuffix(v string) predicate.CoverVersion {
	return predicate.CoverVersion(sql.FieldHasSuffix(FieldFormat, v))
}

// FormatEqualFold applies the EqualFold predicate on the "format" field.
func FormatEqualFold(v string) predicate.CoverVersion {
	return predicate.CoverVersion(sql.FieldEqualFold(FieldFormat, v))
}

// FormatContainsFold applies the ContainsFold predicate on the "format" field.
func FormatContainsFold(v string) predicate.CoverVersion {
	return predicate.CoverVersion(sql.FieldContainsFold(FieldFormat, v))
}

// SizeEQ applies the EQ predicate on the "size" field.
func SizeEQ(v int64) predicate.CoverVersion {
	return predicate.CoverVersion(sql.FieldEQ(FieldSize, v))
}

// SizeNEQ applies the NEQ predicate on the "size" field.
func SizeNEQ(v int64) predicate.CoverVersion {
	return predicate.CoverVersion(sql.FieldNEQ(FieldSize, v))
}

// SizeIn applies the In predicate on the "size" field.
func SizeIn(vs ...int64) predicate.CoverVersion {
	return predicate.CoverVersion(sql.FieldIn(FieldSize, vs...))
}

// SizeNotIn applies the NotIn predicate on the "size" field.
func SizeNotIn(vs ...int64) predicate.CoverVersion {
	return predicate.CoverVersion(sql.FieldNotIn(FieldSize, vs...))
}

// SizeGT applies the GT predicate on the "size" field.
func SizeGT(v int64) predicate.CoverVersion {
	return predicate.CoverVersion(sql.FieldGT(FieldSize, v))
}

// SizeGTE applies the GTE predicate on the "size" field.
func SizeGTE(v int64) predicate.CoverVersion {
	return predicate.CoverVersion(sql.FieldGTE(FieldSize, v))
}

// SizeLT applies the LT predicate on the "size" field.
func SizeLT(v int64) predicate.CoverVersion {
	return predicate.CoverVersion(sql.FieldLT(FieldSize, v))
}

// SizeLTE applies the LTE predicate on the "size" field.
func SizeLTE(v int64) predicate.CoverVersion {
	return predicate.CoverVersion(sql.FieldLTE(FieldSize, v))
}

// SuffixEQ applies the EQ predicate on the "suffix" field.
func SuffixEQ(v string) predicate.CoverVersion {
	return predicate.CoverVersion(sql.FieldEQ(FieldSuffix, v))
}

// SuffixNEQ applies the NEQ predicate on the "suffix" field.
func SuffixNEQ(v string) predicate.CoverVersion {
	return predicate.CoverVersion(sql.FieldNEQ(FieldSuffix, v))
}

// SuffixIn applies the In predicate on the "suffix" field.
func SuffixIn(vs ...string) predicate.CoverVersion {
	return predicate.CoverVersion(sql.FieldIn(FieldSuffix, vs...))
}

// SuffixNotIn applies the NotIn predicate on the "suffix" field.
func SuffixNotIn(vs ...string) predicate.CoverVersion {
	return predicate.CoverVersion(sql.FieldNotIn(FieldSuffix, vs...))
}

// SuffixGT applies the GT predicate on the "suffix" field.
func SuffixGT(v string) predicate.CoverVersion {
	return predicate.CoverVersion(sql.FieldGT(FieldSuffix, v))
}

// SuffixGTE applies the GTE predicate on the "suffix" field.
func SuffixGTE(v string) predicate.CoverVersion {
	return predicate.CoverVersion(sql.FieldGTE(FieldSuffix, v))
}

// SuffixLT applies the LT predicate on the "suffix" field.
func SuffixLT(v string) predicate.CoverVersion {
	return predicate.CoverVersion(sql.FieldLT(FieldSuffix, v))
}

// SuffixLTE applies the LTE predicate on the "suffix" field.
func SuffixLTE(v string) predicate.CoverVersion {
	return predicate.CoverVersion(sql.FieldLTE(FieldSuffix, v))
}

// SuffixContains applies the Contains predicate on the "suffix" field.
func SuffixContains(v string) predicate.CoverVersion {
	return predicate.CoverVersion(sql.FieldContains(FieldSuffix, v))
}

// SuffixHasPrefix applies the HasPrefix predicate on the "suffix" field.
func SuffixHasPrefix(v string) predicate.CoverVersion {
	return predicate.CoverVersion(sql.FieldHasPrefix(FieldSuffix, v))
}

// SuffixHasSuffix applies the HasSuffix predicate on the "suffix" field.
func SuffixHasSuffix(v string) predicate.CoverVersion {
	return predicate.CoverVersion(sql.FieldHasSuffix(FieldSuffix, v))
}

// SuffixEqualFold applies the EqualFold predicate on the "suffix" field.
func SuffixEqualFold(v string) predicate.CoverVersion {
	return predicate.CoverVersion(sql.FieldEqualFold(FieldSuffix, v))
}

// SuffixContainsFold applies the ContainsFold predicate on the "suffix" field.
func SuffixContainsFold(v string) predicate.CoverVersion {
	return predicate.CoverVersion(sql.FieldContainsFold(FieldSuffix, v))
}

// UpdatedAtEQ applies the EQ predicate on the "updated_at" field.
func UpdatedAtEQ(v time.Time) predicate.CoverVersion {
	return predicate.CoverVersion(sql.FieldEQ(FieldUpdatedAt, v))
}

// UpdatedAtNEQ applies the NEQ predicate on the "updated_at" field.
func UpdatedAtNEQ(v time.Time) predicate.CoverVersion {
	return predicate.CoverVersion(sql.FieldNEQ(FieldUpdatedAt, v))
}

// UpdatedAtIn applies the In predicate on the "updated_at" field.
func UpdatedAtIn(vs ...time.Time) predicate.CoverVersion {
	return predicate.CoverVersion(sql.FieldIn(FieldUpdatedAt, vs...))
}

// UpdatedAtNotIn applies the NotIn predicate on the "updated_at" field.
func UpdatedAtNotIn(vs ...time.Time) predicate.CoverVersion {
	return predicate.CoverVersion(sql.FieldNotIn(FieldUpdatedAt, vs...))
}

// UpdatedAtGT applies the GT predicate on the "updated_at" field.
func UpdatedAtGT(v time.Time) predicate.CoverVersion {
	return predicate.CoverVersion(sql.FieldGT(FieldUpdatedAt, v))
}

// UpdatedAtGTE applies the GTE predicate on the "updated_at" field.
func UpdatedAtGTE(v time.Time) predicate.CoverVersion {
	return predicate.CoverVersion(sql.FieldGTE(FieldUpdatedAt, v))
}

// UpdatedAtLT applies the LT predicate on the "updated_at" field.
func UpdatedAtLT(v time.Time) predicate.CoverVersion {
	return predicate.CoverVersion(sql.FieldLT(FieldUpdatedAt, v))
}

// UpdatedAtLTE applies the LTE predicate on the "updated_at" field.
func UpdatedAtLTE(v time.Time) predicate.CoverVersion {
	return predicate.CoverVersion(sql.FieldLTE(FieldUpdatedAt, v))
}

// HasCover applies the HasEdge predicate on the "cover" edge.
func HasCover() predicate.CoverVersion {
	return predicate.CoverVersion(func(s *sql.Selector) {
		step := sqlgraph.NewStep(
			sqlgraph.From(Table, FieldID),
			sqlgraph.Edge(sqlgraph.M2O, true, CoverTable, CoverColumn),
		)
		sqlgraph.HasNeighbors(s, step)
	})
}

// HasCoverWith applies the HasEdge predicate on the "cover" edge with a given conditions (other predicates).
func HasCoverWith(preds ...predicate.BookCover) predicate.CoverVersion {
	return predicate.CoverVersion(func(s *sql.Selector) {
		step := newCoverStep()
		sqlgraph.HasNeighborsWith(s, step, func(s *sql.Selector) {
			for _, p := range preds {
				p(s)
			}
		})
	})
}

// And groups predicates with the AND operator between them.
func And(predicates ...predicate.CoverVersion) predicate.CoverVersion {
	return predicate.CoverVersion(sql.AndPredicates(predicates...))
}

// Or groups predicates with the OR operator between them.
func Or(predicates ...predicate.CoverVersion) predicate.CoverVersion {
	return predicate.CoverVersion(sql.OrPredicates(predicates...))
}

// Not applies the not operator on the given predicate.
func Not(p predicate.CoverVersion) predicate.CoverVersion {
	return predicate.CoverVersion(sql.NotPredicates(p))
}
