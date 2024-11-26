// Code generated by ent, DO NOT EDIT.

package ent

import (
	"context"
	"errors"
	"fmt"
	"time"

	"entgo.io/ent/dialect/sql/sqlgraph"
	"entgo.io/ent/schema/field"
	"github.com/ListenUpApp/ListenUp/internal/ent/folder"
	"github.com/ListenUpApp/ListenUp/internal/ent/library"
)

// FolderCreate is the builder for creating a Folder entity.
type FolderCreate struct {
	config
	mutation *FolderMutation
	hooks    []Hook
}

// SetName sets the "name" field.
func (fc *FolderCreate) SetName(s string) *FolderCreate {
	fc.mutation.SetName(s)
	return fc
}

// SetPath sets the "path" field.
func (fc *FolderCreate) SetPath(s string) *FolderCreate {
	fc.mutation.SetPath(s)
	return fc
}

// SetLastScannedAt sets the "last_scanned_at" field.
func (fc *FolderCreate) SetLastScannedAt(t time.Time) *FolderCreate {
	fc.mutation.SetLastScannedAt(t)
	return fc
}

// SetNillableLastScannedAt sets the "last_scanned_at" field if the given value is not nil.
func (fc *FolderCreate) SetNillableLastScannedAt(t *time.Time) *FolderCreate {
	if t != nil {
		fc.SetLastScannedAt(*t)
	}
	return fc
}

// SetID sets the "id" field.
func (fc *FolderCreate) SetID(s string) *FolderCreate {
	fc.mutation.SetID(s)
	return fc
}

// SetNillableID sets the "id" field if the given value is not nil.
func (fc *FolderCreate) SetNillableID(s *string) *FolderCreate {
	if s != nil {
		fc.SetID(*s)
	}
	return fc
}

// AddLibraryIDs adds the "libraries" edge to the Library entity by IDs.
func (fc *FolderCreate) AddLibraryIDs(ids ...string) *FolderCreate {
	fc.mutation.AddLibraryIDs(ids...)
	return fc
}

// AddLibraries adds the "libraries" edges to the Library entity.
func (fc *FolderCreate) AddLibraries(l ...*Library) *FolderCreate {
	ids := make([]string, len(l))
	for i := range l {
		ids[i] = l[i].ID
	}
	return fc.AddLibraryIDs(ids...)
}

// Mutation returns the FolderMutation object of the builder.
func (fc *FolderCreate) Mutation() *FolderMutation {
	return fc.mutation
}

// Save creates the Folder in the database.
func (fc *FolderCreate) Save(ctx context.Context) (*Folder, error) {
	fc.defaults()
	return withHooks(ctx, fc.sqlSave, fc.mutation, fc.hooks)
}

// SaveX calls Save and panics if Save returns an error.
func (fc *FolderCreate) SaveX(ctx context.Context) *Folder {
	v, err := fc.Save(ctx)
	if err != nil {
		panic(err)
	}
	return v
}

// Exec executes the query.
func (fc *FolderCreate) Exec(ctx context.Context) error {
	_, err := fc.Save(ctx)
	return err
}

// ExecX is like Exec, but panics if an error occurs.
func (fc *FolderCreate) ExecX(ctx context.Context) {
	if err := fc.Exec(ctx); err != nil {
		panic(err)
	}
}

// defaults sets the default values of the builder before save.
func (fc *FolderCreate) defaults() {
	if _, ok := fc.mutation.ID(); !ok {
		v := folder.DefaultID()
		fc.mutation.SetID(v)
	}
}

// check runs all checks and user-defined validators on the builder.
func (fc *FolderCreate) check() error {
	if _, ok := fc.mutation.Name(); !ok {
		return &ValidationError{Name: "name", err: errors.New(`ent: missing required field "Folder.name"`)}
	}
	if _, ok := fc.mutation.Path(); !ok {
		return &ValidationError{Name: "path", err: errors.New(`ent: missing required field "Folder.path"`)}
	}
	return nil
}

func (fc *FolderCreate) sqlSave(ctx context.Context) (*Folder, error) {
	if err := fc.check(); err != nil {
		return nil, err
	}
	_node, _spec := fc.createSpec()
	if err := sqlgraph.CreateNode(ctx, fc.driver, _spec); err != nil {
		if sqlgraph.IsConstraintError(err) {
			err = &ConstraintError{msg: err.Error(), wrap: err}
		}
		return nil, err
	}
	if _spec.ID.Value != nil {
		if id, ok := _spec.ID.Value.(string); ok {
			_node.ID = id
		} else {
			return nil, fmt.Errorf("unexpected Folder.ID type: %T", _spec.ID.Value)
		}
	}
	fc.mutation.id = &_node.ID
	fc.mutation.done = true
	return _node, nil
}

func (fc *FolderCreate) createSpec() (*Folder, *sqlgraph.CreateSpec) {
	var (
		_node = &Folder{config: fc.config}
		_spec = sqlgraph.NewCreateSpec(folder.Table, sqlgraph.NewFieldSpec(folder.FieldID, field.TypeString))
	)
	if id, ok := fc.mutation.ID(); ok {
		_node.ID = id
		_spec.ID.Value = id
	}
	if value, ok := fc.mutation.Name(); ok {
		_spec.SetField(folder.FieldName, field.TypeString, value)
		_node.Name = value
	}
	if value, ok := fc.mutation.Path(); ok {
		_spec.SetField(folder.FieldPath, field.TypeString, value)
		_node.Path = value
	}
	if value, ok := fc.mutation.LastScannedAt(); ok {
		_spec.SetField(folder.FieldLastScannedAt, field.TypeTime, value)
		_node.LastScannedAt = value
	}
	if nodes := fc.mutation.LibrariesIDs(); len(nodes) > 0 {
		edge := &sqlgraph.EdgeSpec{
			Rel:     sqlgraph.M2M,
			Inverse: true,
			Table:   folder.LibrariesTable,
			Columns: folder.LibrariesPrimaryKey,
			Bidi:    false,
			Target: &sqlgraph.EdgeTarget{
				IDSpec: sqlgraph.NewFieldSpec(library.FieldID, field.TypeString),
			},
		}
		for _, k := range nodes {
			edge.Target.Nodes = append(edge.Target.Nodes, k)
		}
		_spec.Edges = append(_spec.Edges, edge)
	}
	return _node, _spec
}

// FolderCreateBulk is the builder for creating many Folder entities in bulk.
type FolderCreateBulk struct {
	config
	err      error
	builders []*FolderCreate
}

// Save creates the Folder entities in the database.
func (fcb *FolderCreateBulk) Save(ctx context.Context) ([]*Folder, error) {
	if fcb.err != nil {
		return nil, fcb.err
	}
	specs := make([]*sqlgraph.CreateSpec, len(fcb.builders))
	nodes := make([]*Folder, len(fcb.builders))
	mutators := make([]Mutator, len(fcb.builders))
	for i := range fcb.builders {
		func(i int, root context.Context) {
			builder := fcb.builders[i]
			builder.defaults()
			var mut Mutator = MutateFunc(func(ctx context.Context, m Mutation) (Value, error) {
				mutation, ok := m.(*FolderMutation)
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
					_, err = mutators[i+1].Mutate(root, fcb.builders[i+1].mutation)
				} else {
					spec := &sqlgraph.BatchCreateSpec{Nodes: specs}
					// Invoke the actual operation on the latest mutation in the chain.
					if err = sqlgraph.BatchCreate(ctx, fcb.driver, spec); err != nil {
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
		if _, err := mutators[0].Mutate(ctx, fcb.builders[0].mutation); err != nil {
			return nil, err
		}
	}
	return nodes, nil
}

// SaveX is like Save, but panics if an error occurs.
func (fcb *FolderCreateBulk) SaveX(ctx context.Context) []*Folder {
	v, err := fcb.Save(ctx)
	if err != nil {
		panic(err)
	}
	return v
}

// Exec executes the query.
func (fcb *FolderCreateBulk) Exec(ctx context.Context) error {
	_, err := fcb.Save(ctx)
	return err
}

// ExecX is like Exec, but panics if an error occurs.
func (fcb *FolderCreateBulk) ExecX(ctx context.Context) {
	if err := fcb.Exec(ctx); err != nil {
		panic(err)
	}
}
