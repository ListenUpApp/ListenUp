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
	"github.com/ListenUpApp/ListenUp/internal/ent/narrator"
)

// NarratorCreate is the builder for creating a Narrator entity.
type NarratorCreate struct {
	config
	mutation *NarratorMutation
	hooks    []Hook
}

// SetName sets the "name" field.
func (nc *NarratorCreate) SetName(s string) *NarratorCreate {
	nc.mutation.SetName(s)
	return nc
}

// SetDescription sets the "description" field.
func (nc *NarratorCreate) SetDescription(s string) *NarratorCreate {
	nc.mutation.SetDescription(s)
	return nc
}

// SetNillableDescription sets the "description" field if the given value is not nil.
func (nc *NarratorCreate) SetNillableDescription(s *string) *NarratorCreate {
	if s != nil {
		nc.SetDescription(*s)
	}
	return nc
}

// SetImagePath sets the "image_path" field.
func (nc *NarratorCreate) SetImagePath(s string) *NarratorCreate {
	nc.mutation.SetImagePath(s)
	return nc
}

// SetNillableImagePath sets the "image_path" field if the given value is not nil.
func (nc *NarratorCreate) SetNillableImagePath(s *string) *NarratorCreate {
	if s != nil {
		nc.SetImagePath(*s)
	}
	return nc
}

// SetCreatedAt sets the "created_at" field.
func (nc *NarratorCreate) SetCreatedAt(t time.Time) *NarratorCreate {
	nc.mutation.SetCreatedAt(t)
	return nc
}

// SetNillableCreatedAt sets the "created_at" field if the given value is not nil.
func (nc *NarratorCreate) SetNillableCreatedAt(t *time.Time) *NarratorCreate {
	if t != nil {
		nc.SetCreatedAt(*t)
	}
	return nc
}

// SetUpdatedAt sets the "updated_at" field.
func (nc *NarratorCreate) SetUpdatedAt(t time.Time) *NarratorCreate {
	nc.mutation.SetUpdatedAt(t)
	return nc
}

// SetNillableUpdatedAt sets the "updated_at" field if the given value is not nil.
func (nc *NarratorCreate) SetNillableUpdatedAt(t *time.Time) *NarratorCreate {
	if t != nil {
		nc.SetUpdatedAt(*t)
	}
	return nc
}

// SetID sets the "id" field.
func (nc *NarratorCreate) SetID(s string) *NarratorCreate {
	nc.mutation.SetID(s)
	return nc
}

// SetNillableID sets the "id" field if the given value is not nil.
func (nc *NarratorCreate) SetNillableID(s *string) *NarratorCreate {
	if s != nil {
		nc.SetID(*s)
	}
	return nc
}

// AddBookIDs adds the "books" edge to the Book entity by IDs.
func (nc *NarratorCreate) AddBookIDs(ids ...string) *NarratorCreate {
	nc.mutation.AddBookIDs(ids...)
	return nc
}

// AddBooks adds the "books" edges to the Book entity.
func (nc *NarratorCreate) AddBooks(b ...*Book) *NarratorCreate {
	ids := make([]string, len(b))
	for i := range b {
		ids[i] = b[i].ID
	}
	return nc.AddBookIDs(ids...)
}

// Mutation returns the NarratorMutation object of the builder.
func (nc *NarratorCreate) Mutation() *NarratorMutation {
	return nc.mutation
}

// Save creates the Narrator in the database.
func (nc *NarratorCreate) Save(ctx context.Context) (*Narrator, error) {
	nc.defaults()
	return withHooks(ctx, nc.sqlSave, nc.mutation, nc.hooks)
}

// SaveX calls Save and panics if Save returns an error.
func (nc *NarratorCreate) SaveX(ctx context.Context) *Narrator {
	v, err := nc.Save(ctx)
	if err != nil {
		panic(err)
	}
	return v
}

// Exec executes the query.
func (nc *NarratorCreate) Exec(ctx context.Context) error {
	_, err := nc.Save(ctx)
	return err
}

// ExecX is like Exec, but panics if an error occurs.
func (nc *NarratorCreate) ExecX(ctx context.Context) {
	if err := nc.Exec(ctx); err != nil {
		panic(err)
	}
}

// defaults sets the default values of the builder before save.
func (nc *NarratorCreate) defaults() {
	if _, ok := nc.mutation.CreatedAt(); !ok {
		v := narrator.DefaultCreatedAt()
		nc.mutation.SetCreatedAt(v)
	}
	if _, ok := nc.mutation.UpdatedAt(); !ok {
		v := narrator.DefaultUpdatedAt()
		nc.mutation.SetUpdatedAt(v)
	}
	if _, ok := nc.mutation.ID(); !ok {
		v := narrator.DefaultID()
		nc.mutation.SetID(v)
	}
}

// check runs all checks and user-defined validators on the builder.
func (nc *NarratorCreate) check() error {
	if _, ok := nc.mutation.Name(); !ok {
		return &ValidationError{Name: "name", err: errors.New(`ent: missing required field "Narrator.name"`)}
	}
	if _, ok := nc.mutation.CreatedAt(); !ok {
		return &ValidationError{Name: "created_at", err: errors.New(`ent: missing required field "Narrator.created_at"`)}
	}
	if _, ok := nc.mutation.UpdatedAt(); !ok {
		return &ValidationError{Name: "updated_at", err: errors.New(`ent: missing required field "Narrator.updated_at"`)}
	}
	return nil
}

func (nc *NarratorCreate) sqlSave(ctx context.Context) (*Narrator, error) {
	if err := nc.check(); err != nil {
		return nil, err
	}
	_node, _spec := nc.createSpec()
	if err := sqlgraph.CreateNode(ctx, nc.driver, _spec); err != nil {
		if sqlgraph.IsConstraintError(err) {
			err = &ConstraintError{msg: err.Error(), wrap: err}
		}
		return nil, err
	}
	if _spec.ID.Value != nil {
		if id, ok := _spec.ID.Value.(string); ok {
			_node.ID = id
		} else {
			return nil, fmt.Errorf("unexpected Narrator.ID type: %T", _spec.ID.Value)
		}
	}
	nc.mutation.id = &_node.ID
	nc.mutation.done = true
	return _node, nil
}

func (nc *NarratorCreate) createSpec() (*Narrator, *sqlgraph.CreateSpec) {
	var (
		_node = &Narrator{config: nc.config}
		_spec = sqlgraph.NewCreateSpec(narrator.Table, sqlgraph.NewFieldSpec(narrator.FieldID, field.TypeString))
	)
	if id, ok := nc.mutation.ID(); ok {
		_node.ID = id
		_spec.ID.Value = id
	}
	if value, ok := nc.mutation.Name(); ok {
		_spec.SetField(narrator.FieldName, field.TypeString, value)
		_node.Name = value
	}
	if value, ok := nc.mutation.Description(); ok {
		_spec.SetField(narrator.FieldDescription, field.TypeString, value)
		_node.Description = value
	}
	if value, ok := nc.mutation.ImagePath(); ok {
		_spec.SetField(narrator.FieldImagePath, field.TypeString, value)
		_node.ImagePath = value
	}
	if value, ok := nc.mutation.CreatedAt(); ok {
		_spec.SetField(narrator.FieldCreatedAt, field.TypeTime, value)
		_node.CreatedAt = value
	}
	if value, ok := nc.mutation.UpdatedAt(); ok {
		_spec.SetField(narrator.FieldUpdatedAt, field.TypeTime, value)
		_node.UpdatedAt = value
	}
	if nodes := nc.mutation.BooksIDs(); len(nodes) > 0 {
		edge := &sqlgraph.EdgeSpec{
			Rel:     sqlgraph.M2M,
			Inverse: false,
			Table:   narrator.BooksTable,
			Columns: narrator.BooksPrimaryKey,
			Bidi:    false,
			Target: &sqlgraph.EdgeTarget{
				IDSpec: sqlgraph.NewFieldSpec(book.FieldID, field.TypeString),
			},
		}
		for _, k := range nodes {
			edge.Target.Nodes = append(edge.Target.Nodes, k)
		}
		_spec.Edges = append(_spec.Edges, edge)
	}
	return _node, _spec
}

// NarratorCreateBulk is the builder for creating many Narrator entities in bulk.
type NarratorCreateBulk struct {
	config
	err      error
	builders []*NarratorCreate
}

// Save creates the Narrator entities in the database.
func (ncb *NarratorCreateBulk) Save(ctx context.Context) ([]*Narrator, error) {
	if ncb.err != nil {
		return nil, ncb.err
	}
	specs := make([]*sqlgraph.CreateSpec, len(ncb.builders))
	nodes := make([]*Narrator, len(ncb.builders))
	mutators := make([]Mutator, len(ncb.builders))
	for i := range ncb.builders {
		func(i int, root context.Context) {
			builder := ncb.builders[i]
			builder.defaults()
			var mut Mutator = MutateFunc(func(ctx context.Context, m Mutation) (Value, error) {
				mutation, ok := m.(*NarratorMutation)
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
					_, err = mutators[i+1].Mutate(root, ncb.builders[i+1].mutation)
				} else {
					spec := &sqlgraph.BatchCreateSpec{Nodes: specs}
					// Invoke the actual operation on the latest mutation in the chain.
					if err = sqlgraph.BatchCreate(ctx, ncb.driver, spec); err != nil {
						if sqlgraph.IsConstraintError(err) {
							err = &ConstraintError{msg: err.Error(), wrap: err}
						}
					}
				}
				if err != nil {
					return nil, err
				}
				mutation.id = &nodes[i].ID
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
		if _, err := mutators[0].Mutate(ctx, ncb.builders[0].mutation); err != nil {
			return nil, err
		}
	}
	return nodes, nil
}

// SaveX is like Save, but panics if an error occurs.
func (ncb *NarratorCreateBulk) SaveX(ctx context.Context) []*Narrator {
	v, err := ncb.Save(ctx)
	if err != nil {
		panic(err)
	}
	return v
}

// Exec executes the query.
func (ncb *NarratorCreateBulk) Exec(ctx context.Context) error {
	_, err := ncb.Save(ctx)
	return err
}

// ExecX is like Exec, but panics if an error occurs.
func (ncb *NarratorCreateBulk) ExecX(ctx context.Context) {
	if err := ncb.Exec(ctx); err != nil {
		panic(err)
	}
}
