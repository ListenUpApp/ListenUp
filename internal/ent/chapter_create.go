// Code generated by ent, DO NOT EDIT.

package ent

import (
	"context"
	"errors"
	"fmt"

	"entgo.io/ent/dialect/sql/sqlgraph"
	"entgo.io/ent/schema/field"
	"github.com/ListenUpApp/ListenUp/internal/ent/book"
	"github.com/ListenUpApp/ListenUp/internal/ent/chapter"
)

// ChapterCreate is the builder for creating a Chapter entity.
type ChapterCreate struct {
	config
	mutation *ChapterMutation
	hooks    []Hook
}

// SetIndex sets the "index" field.
func (cc *ChapterCreate) SetIndex(i int) *ChapterCreate {
	cc.mutation.SetIndex(i)
	return cc
}

// SetTitle sets the "title" field.
func (cc *ChapterCreate) SetTitle(s string) *ChapterCreate {
	cc.mutation.SetTitle(s)
	return cc
}

// SetStart sets the "start" field.
func (cc *ChapterCreate) SetStart(f float64) *ChapterCreate {
	cc.mutation.SetStart(f)
	return cc
}

// SetEnd sets the "end" field.
func (cc *ChapterCreate) SetEnd(f float64) *ChapterCreate {
	cc.mutation.SetEnd(f)
	return cc
}

// SetBookID sets the "book" edge to the Book entity by ID.
func (cc *ChapterCreate) SetBookID(id string) *ChapterCreate {
	cc.mutation.SetBookID(id)
	return cc
}

// SetBook sets the "book" edge to the Book entity.
func (cc *ChapterCreate) SetBook(b *Book) *ChapterCreate {
	return cc.SetBookID(b.ID)
}

// Mutation returns the ChapterMutation object of the builder.
func (cc *ChapterCreate) Mutation() *ChapterMutation {
	return cc.mutation
}

// Save creates the Chapter in the database.
func (cc *ChapterCreate) Save(ctx context.Context) (*Chapter, error) {
	return withHooks(ctx, cc.sqlSave, cc.mutation, cc.hooks)
}

// SaveX calls Save and panics if Save returns an error.
func (cc *ChapterCreate) SaveX(ctx context.Context) *Chapter {
	v, err := cc.Save(ctx)
	if err != nil {
		panic(err)
	}
	return v
}

// Exec executes the query.
func (cc *ChapterCreate) Exec(ctx context.Context) error {
	_, err := cc.Save(ctx)
	return err
}

// ExecX is like Exec, but panics if an error occurs.
func (cc *ChapterCreate) ExecX(ctx context.Context) {
	if err := cc.Exec(ctx); err != nil {
		panic(err)
	}
}

// check runs all checks and user-defined validators on the builder.
func (cc *ChapterCreate) check() error {
	if _, ok := cc.mutation.Index(); !ok {
		return &ValidationError{Name: "index", err: errors.New(`ent: missing required field "Chapter.index"`)}
	}
	if v, ok := cc.mutation.Index(); ok {
		if err := chapter.IndexValidator(v); err != nil {
			return &ValidationError{Name: "index", err: fmt.Errorf(`ent: validator failed for field "Chapter.index": %w`, err)}
		}
	}
	if _, ok := cc.mutation.Title(); !ok {
		return &ValidationError{Name: "title", err: errors.New(`ent: missing required field "Chapter.title"`)}
	}
	if v, ok := cc.mutation.Title(); ok {
		if err := chapter.TitleValidator(v); err != nil {
			return &ValidationError{Name: "title", err: fmt.Errorf(`ent: validator failed for field "Chapter.title": %w`, err)}
		}
	}
	if _, ok := cc.mutation.Start(); !ok {
		return &ValidationError{Name: "start", err: errors.New(`ent: missing required field "Chapter.start"`)}
	}
	if v, ok := cc.mutation.Start(); ok {
		if err := chapter.StartValidator(v); err != nil {
			return &ValidationError{Name: "start", err: fmt.Errorf(`ent: validator failed for field "Chapter.start": %w`, err)}
		}
	}
	if _, ok := cc.mutation.End(); !ok {
		return &ValidationError{Name: "end", err: errors.New(`ent: missing required field "Chapter.end"`)}
	}
	if v, ok := cc.mutation.End(); ok {
		if err := chapter.EndValidator(v); err != nil {
			return &ValidationError{Name: "end", err: fmt.Errorf(`ent: validator failed for field "Chapter.end": %w`, err)}
		}
	}
	if len(cc.mutation.BookIDs()) == 0 {
		return &ValidationError{Name: "book", err: errors.New(`ent: missing required edge "Chapter.book"`)}
	}
	return nil
}

func (cc *ChapterCreate) sqlSave(ctx context.Context) (*Chapter, error) {
	if err := cc.check(); err != nil {
		return nil, err
	}
	_node, _spec := cc.createSpec()
	if err := sqlgraph.CreateNode(ctx, cc.driver, _spec); err != nil {
		if sqlgraph.IsConstraintError(err) {
			err = &ConstraintError{msg: err.Error(), wrap: err}
		}
		return nil, err
	}
	id := _spec.ID.Value.(int64)
	_node.ID = int(id)
	cc.mutation.id = &_node.ID
	cc.mutation.done = true
	return _node, nil
}

func (cc *ChapterCreate) createSpec() (*Chapter, *sqlgraph.CreateSpec) {
	var (
		_node = &Chapter{config: cc.config}
		_spec = sqlgraph.NewCreateSpec(chapter.Table, sqlgraph.NewFieldSpec(chapter.FieldID, field.TypeInt))
	)
	if value, ok := cc.mutation.Index(); ok {
		_spec.SetField(chapter.FieldIndex, field.TypeInt, value)
		_node.Index = value
	}
	if value, ok := cc.mutation.Title(); ok {
		_spec.SetField(chapter.FieldTitle, field.TypeString, value)
		_node.Title = value
	}
	if value, ok := cc.mutation.Start(); ok {
		_spec.SetField(chapter.FieldStart, field.TypeFloat64, value)
		_node.Start = value
	}
	if value, ok := cc.mutation.End(); ok {
		_spec.SetField(chapter.FieldEnd, field.TypeFloat64, value)
		_node.End = value
	}
	if nodes := cc.mutation.BookIDs(); len(nodes) > 0 {
		edge := &sqlgraph.EdgeSpec{
			Rel:     sqlgraph.M2O,
			Inverse: true,
			Table:   chapter.BookTable,
			Columns: []string{chapter.BookColumn},
			Bidi:    false,
			Target: &sqlgraph.EdgeTarget{
				IDSpec: sqlgraph.NewFieldSpec(book.FieldID, field.TypeString),
			},
		}
		for _, k := range nodes {
			edge.Target.Nodes = append(edge.Target.Nodes, k)
		}
		_node.book_chapters = &nodes[0]
		_spec.Edges = append(_spec.Edges, edge)
	}
	return _node, _spec
}

// ChapterCreateBulk is the builder for creating many Chapter entities in bulk.
type ChapterCreateBulk struct {
	config
	err      error
	builders []*ChapterCreate
}

// Save creates the Chapter entities in the database.
func (ccb *ChapterCreateBulk) Save(ctx context.Context) ([]*Chapter, error) {
	if ccb.err != nil {
		return nil, ccb.err
	}
	specs := make([]*sqlgraph.CreateSpec, len(ccb.builders))
	nodes := make([]*Chapter, len(ccb.builders))
	mutators := make([]Mutator, len(ccb.builders))
	for i := range ccb.builders {
		func(i int, root context.Context) {
			builder := ccb.builders[i]
			var mut Mutator = MutateFunc(func(ctx context.Context, m Mutation) (Value, error) {
				mutation, ok := m.(*ChapterMutation)
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
					_, err = mutators[i+1].Mutate(root, ccb.builders[i+1].mutation)
				} else {
					spec := &sqlgraph.BatchCreateSpec{Nodes: specs}
					// Invoke the actual operation on the latest mutation in the chain.
					if err = sqlgraph.BatchCreate(ctx, ccb.driver, spec); err != nil {
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
		if _, err := mutators[0].Mutate(ctx, ccb.builders[0].mutation); err != nil {
			return nil, err
		}
	}
	return nodes, nil
}

// SaveX is like Save, but panics if an error occurs.
func (ccb *ChapterCreateBulk) SaveX(ctx context.Context) []*Chapter {
	v, err := ccb.Save(ctx)
	if err != nil {
		panic(err)
	}
	return v
}

// Exec executes the query.
func (ccb *ChapterCreateBulk) Exec(ctx context.Context) error {
	_, err := ccb.Save(ctx)
	return err
}

// ExecX is like Exec, but panics if an error occurs.
func (ccb *ChapterCreateBulk) ExecX(ctx context.Context) {
	if err := ccb.Exec(ctx); err != nil {
		panic(err)
	}
}
