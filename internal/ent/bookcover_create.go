// Code generated by ent, DO NOT EDIT.

package ent

import (
	"context"
	"errors"
	"fmt"
	"time"

	"entgo.io/ent/dialect/sql/sqlgraph"
	"entgo.io/ent/schema/field"
	"github.com/ListenUpApp/ListenUp/internal/ent/book"
	"github.com/ListenUpApp/ListenUp/internal/ent/bookcover"
)

// BookCoverCreate is the builder for creating a BookCover entity.
type BookCoverCreate struct {
	config
	mutation *BookCoverMutation
	hooks    []Hook
}

// SetPath sets the "path" field.
func (bcc *BookCoverCreate) SetPath(s string) *BookCoverCreate {
	bcc.mutation.SetPath(s)
	return bcc
}

// SetFormat sets the "format" field.
func (bcc *BookCoverCreate) SetFormat(s string) *BookCoverCreate {
	bcc.mutation.SetFormat(s)
	return bcc
}

// SetSize sets the "size" field.
func (bcc *BookCoverCreate) SetSize(i int64) *BookCoverCreate {
	bcc.mutation.SetSize(i)
	return bcc
}

// SetUpdatedAt sets the "updated_at" field.
func (bcc *BookCoverCreate) SetUpdatedAt(t time.Time) *BookCoverCreate {
	bcc.mutation.SetUpdatedAt(t)
	return bcc
}

// SetNillableUpdatedAt sets the "updated_at" field if the given value is not nil.
func (bcc *BookCoverCreate) SetNillableUpdatedAt(t *time.Time) *BookCoverCreate {
	if t != nil {
		bcc.SetUpdatedAt(*t)
	}
	return bcc
}

// SetBookID sets the "book" edge to the Book entity by ID.
func (bcc *BookCoverCreate) SetBookID(id string) *BookCoverCreate {
	bcc.mutation.SetBookID(id)
	return bcc
}

// SetBook sets the "book" edge to the Book entity.
func (bcc *BookCoverCreate) SetBook(b *Book) *BookCoverCreate {
	return bcc.SetBookID(b.ID)
}

// Mutation returns the BookCoverMutation object of the builder.
func (bcc *BookCoverCreate) Mutation() *BookCoverMutation {
	return bcc.mutation
}

// Save creates the BookCover in the database.
func (bcc *BookCoverCreate) Save(ctx context.Context) (*BookCover, error) {
	bcc.defaults()
	return withHooks(ctx, bcc.sqlSave, bcc.mutation, bcc.hooks)
}

// SaveX calls Save and panics if Save returns an error.
func (bcc *BookCoverCreate) SaveX(ctx context.Context) *BookCover {
	v, err := bcc.Save(ctx)
	if err != nil {
		panic(err)
	}
	return v
}

// Exec executes the query.
func (bcc *BookCoverCreate) Exec(ctx context.Context) error {
	_, err := bcc.Save(ctx)
	return err
}

// ExecX is like Exec, but panics if an error occurs.
func (bcc *BookCoverCreate) ExecX(ctx context.Context) {
	if err := bcc.Exec(ctx); err != nil {
		panic(err)
	}
}

// defaults sets the default values of the builder before save.
func (bcc *BookCoverCreate) defaults() {
	if _, ok := bcc.mutation.UpdatedAt(); !ok {
		v := bookcover.DefaultUpdatedAt()
		bcc.mutation.SetUpdatedAt(v)
	}
}

// check runs all checks and user-defined validators on the builder.
func (bcc *BookCoverCreate) check() error {
	if _, ok := bcc.mutation.Path(); !ok {
		return &ValidationError{Name: "path", err: errors.New(`ent: missing required field "BookCover.path"`)}
	}
	if v, ok := bcc.mutation.Path(); ok {
		if err := bookcover.PathValidator(v); err != nil {
			return &ValidationError{Name: "path", err: fmt.Errorf(`ent: validator failed for field "BookCover.path": %w`, err)}
		}
	}
	if _, ok := bcc.mutation.Format(); !ok {
		return &ValidationError{Name: "format", err: errors.New(`ent: missing required field "BookCover.format"`)}
	}
	if v, ok := bcc.mutation.Format(); ok {
		if err := bookcover.FormatValidator(v); err != nil {
			return &ValidationError{Name: "format", err: fmt.Errorf(`ent: validator failed for field "BookCover.format": %w`, err)}
		}
	}
	if _, ok := bcc.mutation.Size(); !ok {
		return &ValidationError{Name: "size", err: errors.New(`ent: missing required field "BookCover.size"`)}
	}
	if v, ok := bcc.mutation.Size(); ok {
		if err := bookcover.SizeValidator(v); err != nil {
			return &ValidationError{Name: "size", err: fmt.Errorf(`ent: validator failed for field "BookCover.size": %w`, err)}
		}
	}
	if _, ok := bcc.mutation.UpdatedAt(); !ok {
		return &ValidationError{Name: "updated_at", err: errors.New(`ent: missing required field "BookCover.updated_at"`)}
	}
	if len(bcc.mutation.BookIDs()) == 0 {
		return &ValidationError{Name: "book", err: errors.New(`ent: missing required edge "BookCover.book"`)}
	}
	return nil
}

func (bcc *BookCoverCreate) sqlSave(ctx context.Context) (*BookCover, error) {
	if err := bcc.check(); err != nil {
		return nil, err
	}
	_node, _spec := bcc.createSpec()
	if err := sqlgraph.CreateNode(ctx, bcc.driver, _spec); err != nil {
		if sqlgraph.IsConstraintError(err) {
			err = &ConstraintError{msg: err.Error(), wrap: err}
		}
		return nil, err
	}
	id := _spec.ID.Value.(int64)
	_node.ID = int(id)
	bcc.mutation.id = &_node.ID
	bcc.mutation.done = true
	return _node, nil
}

func (bcc *BookCoverCreate) createSpec() (*BookCover, *sqlgraph.CreateSpec) {
	var (
		_node = &BookCover{config: bcc.config}
		_spec = sqlgraph.NewCreateSpec(bookcover.Table, sqlgraph.NewFieldSpec(bookcover.FieldID, field.TypeInt))
	)
	if value, ok := bcc.mutation.Path(); ok {
		_spec.SetField(bookcover.FieldPath, field.TypeString, value)
		_node.Path = value
	}
	if value, ok := bcc.mutation.Format(); ok {
		_spec.SetField(bookcover.FieldFormat, field.TypeString, value)
		_node.Format = value
	}
	if value, ok := bcc.mutation.Size(); ok {
		_spec.SetField(bookcover.FieldSize, field.TypeInt64, value)
		_node.Size = value
	}
	if value, ok := bcc.mutation.UpdatedAt(); ok {
		_spec.SetField(bookcover.FieldUpdatedAt, field.TypeTime, value)
		_node.UpdatedAt = value
	}
	if nodes := bcc.mutation.BookIDs(); len(nodes) > 0 {
		edge := &sqlgraph.EdgeSpec{
			Rel:     sqlgraph.O2O,
			Inverse: true,
			Table:   bookcover.BookTable,
			Columns: []string{bookcover.BookColumn},
			Bidi:    false,
			Target: &sqlgraph.EdgeTarget{
				IDSpec: sqlgraph.NewFieldSpec(book.FieldID, field.TypeString),
			},
		}
		for _, k := range nodes {
			edge.Target.Nodes = append(edge.Target.Nodes, k)
		}
		_node.book_cover = &nodes[0]
		_spec.Edges = append(_spec.Edges, edge)
	}
	return _node, _spec
}

// BookCoverCreateBulk is the builder for creating many BookCover entities in bulk.
type BookCoverCreateBulk struct {
	config
	err      error
	builders []*BookCoverCreate
}

// Save creates the BookCover entities in the database.
func (bccb *BookCoverCreateBulk) Save(ctx context.Context) ([]*BookCover, error) {
	if bccb.err != nil {
		return nil, bccb.err
	}
	specs := make([]*sqlgraph.CreateSpec, len(bccb.builders))
	nodes := make([]*BookCover, len(bccb.builders))
	mutators := make([]Mutator, len(bccb.builders))
	for i := range bccb.builders {
		func(i int, root context.Context) {
			builder := bccb.builders[i]
			builder.defaults()
			var mut Mutator = MutateFunc(func(ctx context.Context, m Mutation) (Value, error) {
				mutation, ok := m.(*BookCoverMutation)
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
					_, err = mutators[i+1].Mutate(root, bccb.builders[i+1].mutation)
				} else {
					spec := &sqlgraph.BatchCreateSpec{Nodes: specs}
					// Invoke the actual operation on the latest mutation in the chain.
					if err = sqlgraph.BatchCreate(ctx, bccb.driver, spec); err != nil {
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
		if _, err := mutators[0].Mutate(ctx, bccb.builders[0].mutation); err != nil {
			return nil, err
		}
	}
	return nodes, nil
}

// SaveX is like Save, but panics if an error occurs.
func (bccb *BookCoverCreateBulk) SaveX(ctx context.Context) []*BookCover {
	v, err := bccb.Save(ctx)
	if err != nil {
		panic(err)
	}
	return v
}

// Exec executes the query.
func (bccb *BookCoverCreateBulk) Exec(ctx context.Context) error {
	_, err := bccb.Save(ctx)
	return err
}

// ExecX is like Exec, but panics if an error occurs.
func (bccb *BookCoverCreateBulk) ExecX(ctx context.Context) {
	if err := bccb.Exec(ctx); err != nil {
		panic(err)
	}
}
