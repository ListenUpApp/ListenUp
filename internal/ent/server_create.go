// Code generated by ent, DO NOT EDIT.

package ent

import (
	"context"
	"errors"
	"fmt"
	"time"

	"entgo.io/ent/dialect/sql/sqlgraph"
	"entgo.io/ent/schema/field"
	"github.com/ListenUpApp/ListenUp/internal/ent/server"
	"github.com/ListenUpApp/ListenUp/internal/ent/serverconfig"
)

// ServerCreate is the builder for creating a Server entity.
type ServerCreate struct {
	config
	mutation *ServerMutation
	hooks    []Hook
}

// SetSetup sets the "setup" field.
func (sc *ServerCreate) SetSetup(b bool) *ServerCreate {
	sc.mutation.SetSetup(b)
	return sc
}

// SetNillableSetup sets the "setup" field if the given value is not nil.
func (sc *ServerCreate) SetNillableSetup(b *bool) *ServerCreate {
	if b != nil {
		sc.SetSetup(*b)
	}
	return sc
}

// SetCreatedAt sets the "created_at" field.
func (sc *ServerCreate) SetCreatedAt(t time.Time) *ServerCreate {
	sc.mutation.SetCreatedAt(t)
	return sc
}

// SetNillableCreatedAt sets the "created_at" field if the given value is not nil.
func (sc *ServerCreate) SetNillableCreatedAt(t *time.Time) *ServerCreate {
	if t != nil {
		sc.SetCreatedAt(*t)
	}
	return sc
}

// SetUpdatedAt sets the "updated_at" field.
func (sc *ServerCreate) SetUpdatedAt(t time.Time) *ServerCreate {
	sc.mutation.SetUpdatedAt(t)
	return sc
}

// SetNillableUpdatedAt sets the "updated_at" field if the given value is not nil.
func (sc *ServerCreate) SetNillableUpdatedAt(t *time.Time) *ServerCreate {
	if t != nil {
		sc.SetUpdatedAt(*t)
	}
	return sc
}

// SetConfigID sets the "config" edge to the ServerConfig entity by ID.
func (sc *ServerCreate) SetConfigID(id int) *ServerCreate {
	sc.mutation.SetConfigID(id)
	return sc
}

// SetConfig sets the "config" edge to the ServerConfig entity.
func (sc *ServerCreate) SetConfig(s *ServerConfig) *ServerCreate {
	return sc.SetConfigID(s.ID)
}

// Mutation returns the ServerMutation object of the builder.
func (sc *ServerCreate) Mutation() *ServerMutation {
	return sc.mutation
}

// Save creates the Server in the database.
func (sc *ServerCreate) Save(ctx context.Context) (*Server, error) {
	sc.defaults()
	return withHooks(ctx, sc.sqlSave, sc.mutation, sc.hooks)
}

// SaveX calls Save and panics if Save returns an error.
func (sc *ServerCreate) SaveX(ctx context.Context) *Server {
	v, err := sc.Save(ctx)
	if err != nil {
		panic(err)
	}
	return v
}

// Exec executes the query.
func (sc *ServerCreate) Exec(ctx context.Context) error {
	_, err := sc.Save(ctx)
	return err
}

// ExecX is like Exec, but panics if an error occurs.
func (sc *ServerCreate) ExecX(ctx context.Context) {
	if err := sc.Exec(ctx); err != nil {
		panic(err)
	}
}

// defaults sets the default values of the builder before save.
func (sc *ServerCreate) defaults() {
	if _, ok := sc.mutation.Setup(); !ok {
		v := server.DefaultSetup
		sc.mutation.SetSetup(v)
	}
	if _, ok := sc.mutation.CreatedAt(); !ok {
		v := server.DefaultCreatedAt()
		sc.mutation.SetCreatedAt(v)
	}
	if _, ok := sc.mutation.UpdatedAt(); !ok {
		v := server.DefaultUpdatedAt()
		sc.mutation.SetUpdatedAt(v)
	}
}

// check runs all checks and user-defined validators on the builder.
func (sc *ServerCreate) check() error {
	if _, ok := sc.mutation.Setup(); !ok {
		return &ValidationError{Name: "setup", err: errors.New(`ent: missing required field "Server.setup"`)}
	}
	if _, ok := sc.mutation.CreatedAt(); !ok {
		return &ValidationError{Name: "created_at", err: errors.New(`ent: missing required field "Server.created_at"`)}
	}
	if _, ok := sc.mutation.UpdatedAt(); !ok {
		return &ValidationError{Name: "updated_at", err: errors.New(`ent: missing required field "Server.updated_at"`)}
	}
	if len(sc.mutation.ConfigIDs()) == 0 {
		return &ValidationError{Name: "config", err: errors.New(`ent: missing required edge "Server.config"`)}
	}
	return nil
}

func (sc *ServerCreate) sqlSave(ctx context.Context) (*Server, error) {
	if err := sc.check(); err != nil {
		return nil, err
	}
	_node, _spec := sc.createSpec()
	if err := sqlgraph.CreateNode(ctx, sc.driver, _spec); err != nil {
		if sqlgraph.IsConstraintError(err) {
			err = &ConstraintError{msg: err.Error(), wrap: err}
		}
		return nil, err
	}
	id := _spec.ID.Value.(int64)
	_node.ID = int(id)
	sc.mutation.id = &_node.ID
	sc.mutation.done = true
	return _node, nil
}

func (sc *ServerCreate) createSpec() (*Server, *sqlgraph.CreateSpec) {
	var (
		_node = &Server{config: sc.config}
		_spec = sqlgraph.NewCreateSpec(server.Table, sqlgraph.NewFieldSpec(server.FieldID, field.TypeInt))
	)
	if value, ok := sc.mutation.Setup(); ok {
		_spec.SetField(server.FieldSetup, field.TypeBool, value)
		_node.Setup = value
	}
	if value, ok := sc.mutation.CreatedAt(); ok {
		_spec.SetField(server.FieldCreatedAt, field.TypeTime, value)
		_node.CreatedAt = value
	}
	if value, ok := sc.mutation.UpdatedAt(); ok {
		_spec.SetField(server.FieldUpdatedAt, field.TypeTime, value)
		_node.UpdatedAt = value
	}
	if nodes := sc.mutation.ConfigIDs(); len(nodes) > 0 {
		edge := &sqlgraph.EdgeSpec{
			Rel:     sqlgraph.O2O,
			Inverse: false,
			Table:   server.ConfigTable,
			Columns: []string{server.ConfigColumn},
			Bidi:    false,
			Target: &sqlgraph.EdgeTarget{
				IDSpec: sqlgraph.NewFieldSpec(serverconfig.FieldID, field.TypeInt),
			},
		}
		for _, k := range nodes {
			edge.Target.Nodes = append(edge.Target.Nodes, k)
		}
		_spec.Edges = append(_spec.Edges, edge)
	}
	return _node, _spec
}

// ServerCreateBulk is the builder for creating many Server entities in bulk.
type ServerCreateBulk struct {
	config
	err      error
	builders []*ServerCreate
}

// Save creates the Server entities in the database.
func (scb *ServerCreateBulk) Save(ctx context.Context) ([]*Server, error) {
	if scb.err != nil {
		return nil, scb.err
	}
	specs := make([]*sqlgraph.CreateSpec, len(scb.builders))
	nodes := make([]*Server, len(scb.builders))
	mutators := make([]Mutator, len(scb.builders))
	for i := range scb.builders {
		func(i int, root context.Context) {
			builder := scb.builders[i]
			builder.defaults()
			var mut Mutator = MutateFunc(func(ctx context.Context, m Mutation) (Value, error) {
				mutation, ok := m.(*ServerMutation)
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
					_, err = mutators[i+1].Mutate(root, scb.builders[i+1].mutation)
				} else {
					spec := &sqlgraph.BatchCreateSpec{Nodes: specs}
					// Invoke the actual operation on the latest mutation in the chain.
					if err = sqlgraph.BatchCreate(ctx, scb.driver, spec); err != nil {
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
		if _, err := mutators[0].Mutate(ctx, scb.builders[0].mutation); err != nil {
			return nil, err
		}
	}
	return nodes, nil
}

// SaveX is like Save, but panics if an error occurs.
func (scb *ServerCreateBulk) SaveX(ctx context.Context) []*Server {
	v, err := scb.Save(ctx)
	if err != nil {
		panic(err)
	}
	return v
}

// Exec executes the query.
func (scb *ServerCreateBulk) Exec(ctx context.Context) error {
	_, err := scb.Save(ctx)
	return err
}

// ExecX is like Exec, but panics if an error occurs.
func (scb *ServerCreateBulk) ExecX(ctx context.Context) {
	if err := scb.Exec(ctx); err != nil {
		panic(err)
	}
}