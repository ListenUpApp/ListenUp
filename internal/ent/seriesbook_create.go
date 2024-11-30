// Code generated by ent, DO NOT EDIT.

package ent

import (
	"context"
	"errors"
	"fmt"

	"entgo.io/ent/dialect/sql/sqlgraph"
	"entgo.io/ent/schema/field"
	"github.com/ListenUpApp/ListenUp/internal/ent/book"
	"github.com/ListenUpApp/ListenUp/internal/ent/series"
	"github.com/ListenUpApp/ListenUp/internal/ent/seriesbook"
)

// SeriesBookCreate is the builder for creating a SeriesBook entity.
type SeriesBookCreate struct {
	config
	mutation *SeriesBookMutation
	hooks    []Hook
}

// SetSequence sets the "sequence" field.
func (sbc *SeriesBookCreate) SetSequence(f float64) *SeriesBookCreate {
	sbc.mutation.SetSequence(f)
	return sbc
}

// SetSeriesID sets the "series" edge to the Series entity by ID.
func (sbc *SeriesBookCreate) SetSeriesID(id string) *SeriesBookCreate {
	sbc.mutation.SetSeriesID(id)
	return sbc
}

// SetSeries sets the "series" edge to the Series entity.
func (sbc *SeriesBookCreate) SetSeries(s *Series) *SeriesBookCreate {
	return sbc.SetSeriesID(s.ID)
}

// SetBookID sets the "book" edge to the Book entity by ID.
func (sbc *SeriesBookCreate) SetBookID(id string) *SeriesBookCreate {
	sbc.mutation.SetBookID(id)
	return sbc
}

// SetBook sets the "book" edge to the Book entity.
func (sbc *SeriesBookCreate) SetBook(b *Book) *SeriesBookCreate {
	return sbc.SetBookID(b.ID)
}

// Mutation returns the SeriesBookMutation object of the builder.
func (sbc *SeriesBookCreate) Mutation() *SeriesBookMutation {
	return sbc.mutation
}

// Save creates the SeriesBook in the database.
func (sbc *SeriesBookCreate) Save(ctx context.Context) (*SeriesBook, error) {
	return withHooks(ctx, sbc.sqlSave, sbc.mutation, sbc.hooks)
}

// SaveX calls Save and panics if Save returns an error.
func (sbc *SeriesBookCreate) SaveX(ctx context.Context) *SeriesBook {
	v, err := sbc.Save(ctx)
	if err != nil {
		panic(err)
	}
	return v
}

// Exec executes the query.
func (sbc *SeriesBookCreate) Exec(ctx context.Context) error {
	_, err := sbc.Save(ctx)
	return err
}

// ExecX is like Exec, but panics if an error occurs.
func (sbc *SeriesBookCreate) ExecX(ctx context.Context) {
	if err := sbc.Exec(ctx); err != nil {
		panic(err)
	}
}

// check runs all checks and user-defined validators on the builder.
func (sbc *SeriesBookCreate) check() error {
	if _, ok := sbc.mutation.Sequence(); !ok {
		return &ValidationError{Name: "sequence", err: errors.New(`ent: missing required field "SeriesBook.sequence"`)}
	}
	if len(sbc.mutation.SeriesIDs()) == 0 {
		return &ValidationError{Name: "series", err: errors.New(`ent: missing required edge "SeriesBook.series"`)}
	}
	if len(sbc.mutation.BookIDs()) == 0 {
		return &ValidationError{Name: "book", err: errors.New(`ent: missing required edge "SeriesBook.book"`)}
	}
	return nil
}

func (sbc *SeriesBookCreate) sqlSave(ctx context.Context) (*SeriesBook, error) {
	if err := sbc.check(); err != nil {
		return nil, err
	}
	_node, _spec := sbc.createSpec()
	if err := sqlgraph.CreateNode(ctx, sbc.driver, _spec); err != nil {
		if sqlgraph.IsConstraintError(err) {
			err = &ConstraintError{msg: err.Error(), wrap: err}
		}
		return nil, err
	}
	id := _spec.ID.Value.(int64)
	_node.ID = int(id)
	sbc.mutation.id = &_node.ID
	sbc.mutation.done = true
	return _node, nil
}

func (sbc *SeriesBookCreate) createSpec() (*SeriesBook, *sqlgraph.CreateSpec) {
	var (
		_node = &SeriesBook{config: sbc.config}
		_spec = sqlgraph.NewCreateSpec(seriesbook.Table, sqlgraph.NewFieldSpec(seriesbook.FieldID, field.TypeInt))
	)
	if value, ok := sbc.mutation.Sequence(); ok {
		_spec.SetField(seriesbook.FieldSequence, field.TypeFloat64, value)
		_node.Sequence = value
	}
	if nodes := sbc.mutation.SeriesIDs(); len(nodes) > 0 {
		edge := &sqlgraph.EdgeSpec{
			Rel:     sqlgraph.M2O,
			Inverse: true,
			Table:   seriesbook.SeriesTable,
			Columns: []string{seriesbook.SeriesColumn},
			Bidi:    false,
			Target: &sqlgraph.EdgeTarget{
				IDSpec: sqlgraph.NewFieldSpec(series.FieldID, field.TypeString),
			},
		}
		for _, k := range nodes {
			edge.Target.Nodes = append(edge.Target.Nodes, k)
		}
		_node.series_series_books = &nodes[0]
		_spec.Edges = append(_spec.Edges, edge)
	}
	if nodes := sbc.mutation.BookIDs(); len(nodes) > 0 {
		edge := &sqlgraph.EdgeSpec{
			Rel:     sqlgraph.M2O,
			Inverse: true,
			Table:   seriesbook.BookTable,
			Columns: []string{seriesbook.BookColumn},
			Bidi:    false,
			Target: &sqlgraph.EdgeTarget{
				IDSpec: sqlgraph.NewFieldSpec(book.FieldID, field.TypeString),
			},
		}
		for _, k := range nodes {
			edge.Target.Nodes = append(edge.Target.Nodes, k)
		}
		_node.book_series_books = &nodes[0]
		_spec.Edges = append(_spec.Edges, edge)
	}
	return _node, _spec
}

// SeriesBookCreateBulk is the builder for creating many SeriesBook entities in bulk.
type SeriesBookCreateBulk struct {
	config
	err      error
	builders []*SeriesBookCreate
}

// Save creates the SeriesBook entities in the database.
func (sbcb *SeriesBookCreateBulk) Save(ctx context.Context) ([]*SeriesBook, error) {
	if sbcb.err != nil {
		return nil, sbcb.err
	}
	specs := make([]*sqlgraph.CreateSpec, len(sbcb.builders))
	nodes := make([]*SeriesBook, len(sbcb.builders))
	mutators := make([]Mutator, len(sbcb.builders))
	for i := range sbcb.builders {
		func(i int, root context.Context) {
			builder := sbcb.builders[i]
			var mut Mutator = MutateFunc(func(ctx context.Context, m Mutation) (Value, error) {
				mutation, ok := m.(*SeriesBookMutation)
				if !ok {
					return nil, fmt.Errorf("unexpected mutation type %T", m)
				}
				if err := builder.check(); err != nil {
					return nil, err
				}
				builder.mutation = mutation
				var err error
				nodes[i], specs[i] = builder.createSpec()
				if i < len(mutators)-1 {
					_, err = mutators[i+1].Mutate(root, sbcb.builders[i+1].mutation)
				} else {
					spec := &sqlgraph.BatchCreateSpec{Nodes: specs}
					// Invoke the actual operation on the latest mutation in the chain.
					if err = sqlgraph.BatchCreate(ctx, sbcb.driver, spec); err != nil {
						if sqlgraph.IsConstraintError(err) {
							err = &ConstraintError{msg: err.Error(), wrap: err}
						}
					}
				}
				if err != nil {
					return nil, err
				}
				mutation.id = &nodes[i].ID
				if specs[i].ID.Value != nil {
					id := specs[i].ID.Value.(int64)
					nodes[i].ID = int(id)
				}
				mutation.done = true
				return nodes[i], nil
			})
			for i := len(builder.hooks) - 1; i >= 0; i-- {
				mut = builder.hooks[i](mut)
			}
			mutators[i] = mut
		}(i, ctx)
	}
	if len(mutators) > 0 {
		if _, err := mutators[0].Mutate(ctx, sbcb.builders[0].mutation); err != nil {
			return nil, err
		}
	}
	return nodes, nil
}

// SaveX is like Save, but panics if an error occurs.
func (sbcb *SeriesBookCreateBulk) SaveX(ctx context.Context) []*SeriesBook {
	v, err := sbcb.Save(ctx)
	if err != nil {
		panic(err)
	}
	return v
}

// Exec executes the query.
func (sbcb *SeriesBookCreateBulk) Exec(ctx context.Context) error {
	_, err := sbcb.Save(ctx)
	return err
}

// ExecX is like Exec, but panics if an error occurs.
func (sbcb *SeriesBookCreateBulk) ExecX(ctx context.Context) {
	if err := sbcb.Exec(ctx); err != nil {
		panic(err)
	}
}