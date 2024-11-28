// Code generated by ent, DO NOT EDIT.

package ent

import (
	"context"
	"errors"
	"fmt"
	"time"

	"entgo.io/ent/dialect/sql"
	"entgo.io/ent/dialect/sql/sqlgraph"
	"entgo.io/ent/schema/field"
	"github.com/ListenUpApp/ListenUp/internal/ent/author"
	"github.com/ListenUpApp/ListenUp/internal/ent/book"
	"github.com/ListenUpApp/ListenUp/internal/ent/predicate"
)

// AuthorUpdate is the builder for updating Author entities.
type AuthorUpdate struct {
	config
	hooks    []Hook
	mutation *AuthorMutation
}

// Where appends a list predicates to the AuthorUpdate builder.
func (au *AuthorUpdate) Where(ps ...predicate.Author) *AuthorUpdate {
	au.mutation.Where(ps...)
	return au
}

// SetName sets the "name" field.
func (au *AuthorUpdate) SetName(s string) *AuthorUpdate {
	au.mutation.SetName(s)
	return au
}

// SetNillableName sets the "name" field if the given value is not nil.
func (au *AuthorUpdate) SetNillableName(s *string) *AuthorUpdate {
	if s != nil {
		au.SetName(*s)
	}
	return au
}

// SetNameSort sets the "name_sort" field.
func (au *AuthorUpdate) SetNameSort(s string) *AuthorUpdate {
	au.mutation.SetNameSort(s)
	return au
}

// SetNillableNameSort sets the "name_sort" field if the given value is not nil.
func (au *AuthorUpdate) SetNillableNameSort(s *string) *AuthorUpdate {
	if s != nil {
		au.SetNameSort(*s)
	}
	return au
}

// SetDescription sets the "description" field.
func (au *AuthorUpdate) SetDescription(s string) *AuthorUpdate {
	au.mutation.SetDescription(s)
	return au
}

// SetNillableDescription sets the "description" field if the given value is not nil.
func (au *AuthorUpdate) SetNillableDescription(s *string) *AuthorUpdate {
	if s != nil {
		au.SetDescription(*s)
	}
	return au
}

// ClearDescription clears the value of the "description" field.
func (au *AuthorUpdate) ClearDescription() *AuthorUpdate {
	au.mutation.ClearDescription()
	return au
}

// SetImagePath sets the "image_path" field.
func (au *AuthorUpdate) SetImagePath(s string) *AuthorUpdate {
	au.mutation.SetImagePath(s)
	return au
}

// SetNillableImagePath sets the "image_path" field if the given value is not nil.
func (au *AuthorUpdate) SetNillableImagePath(s *string) *AuthorUpdate {
	if s != nil {
		au.SetImagePath(*s)
	}
	return au
}

// ClearImagePath clears the value of the "image_path" field.
func (au *AuthorUpdate) ClearImagePath() *AuthorUpdate {
	au.mutation.ClearImagePath()
	return au
}

// SetCreatedAt sets the "created_at" field.
func (au *AuthorUpdate) SetCreatedAt(t time.Time) *AuthorUpdate {
	au.mutation.SetCreatedAt(t)
	return au
}

// SetNillableCreatedAt sets the "created_at" field if the given value is not nil.
func (au *AuthorUpdate) SetNillableCreatedAt(t *time.Time) *AuthorUpdate {
	if t != nil {
		au.SetCreatedAt(*t)
	}
	return au
}

// SetUpdatedAt sets the "updated_at" field.
func (au *AuthorUpdate) SetUpdatedAt(t time.Time) *AuthorUpdate {
	au.mutation.SetUpdatedAt(t)
	return au
}

// SetNillableUpdatedAt sets the "updated_at" field if the given value is not nil.
func (au *AuthorUpdate) SetNillableUpdatedAt(t *time.Time) *AuthorUpdate {
	if t != nil {
		au.SetUpdatedAt(*t)
	}
	return au
}

// AddBookIDs adds the "books" edge to the Book entity by IDs.
func (au *AuthorUpdate) AddBookIDs(ids ...string) *AuthorUpdate {
	au.mutation.AddBookIDs(ids...)
	return au
}

// AddBooks adds the "books" edges to the Book entity.
func (au *AuthorUpdate) AddBooks(b ...*Book) *AuthorUpdate {
	ids := make([]string, len(b))
	for i := range b {
		ids[i] = b[i].ID
	}
	return au.AddBookIDs(ids...)
}

// Mutation returns the AuthorMutation object of the builder.
func (au *AuthorUpdate) Mutation() *AuthorMutation {
	return au.mutation
}

// ClearBooks clears all "books" edges to the Book entity.
func (au *AuthorUpdate) ClearBooks() *AuthorUpdate {
	au.mutation.ClearBooks()
	return au
}

// RemoveBookIDs removes the "books" edge to Book entities by IDs.
func (au *AuthorUpdate) RemoveBookIDs(ids ...string) *AuthorUpdate {
	au.mutation.RemoveBookIDs(ids...)
	return au
}

// RemoveBooks removes "books" edges to Book entities.
func (au *AuthorUpdate) RemoveBooks(b ...*Book) *AuthorUpdate {
	ids := make([]string, len(b))
	for i := range b {
		ids[i] = b[i].ID
	}
	return au.RemoveBookIDs(ids...)
}

// Save executes the query and returns the number of nodes affected by the update operation.
func (au *AuthorUpdate) Save(ctx context.Context) (int, error) {
	return withHooks(ctx, au.sqlSave, au.mutation, au.hooks)
}

// SaveX is like Save, but panics if an error occurs.
func (au *AuthorUpdate) SaveX(ctx context.Context) int {
	affected, err := au.Save(ctx)
	if err != nil {
		panic(err)
	}
	return affected
}

// Exec executes the query.
func (au *AuthorUpdate) Exec(ctx context.Context) error {
	_, err := au.Save(ctx)
	return err
}

// ExecX is like Exec, but panics if an error occurs.
func (au *AuthorUpdate) ExecX(ctx context.Context) {
	if err := au.Exec(ctx); err != nil {
		panic(err)
	}
}

func (au *AuthorUpdate) sqlSave(ctx context.Context) (n int, err error) {
	_spec := sqlgraph.NewUpdateSpec(author.Table, author.Columns, sqlgraph.NewFieldSpec(author.FieldID, field.TypeString))
	if ps := au.mutation.predicates; len(ps) > 0 {
		_spec.Predicate = func(selector *sql.Selector) {
			for i := range ps {
				ps[i](selector)
			}
		}
	}
	if value, ok := au.mutation.Name(); ok {
		_spec.SetField(author.FieldName, field.TypeString, value)
	}
	if value, ok := au.mutation.NameSort(); ok {
		_spec.SetField(author.FieldNameSort, field.TypeString, value)
	}
	if value, ok := au.mutation.Description(); ok {
		_spec.SetField(author.FieldDescription, field.TypeString, value)
	}
	if au.mutation.DescriptionCleared() {
		_spec.ClearField(author.FieldDescription, field.TypeString)
	}
	if value, ok := au.mutation.ImagePath(); ok {
		_spec.SetField(author.FieldImagePath, field.TypeString, value)
	}
	if au.mutation.ImagePathCleared() {
		_spec.ClearField(author.FieldImagePath, field.TypeString)
	}
	if value, ok := au.mutation.CreatedAt(); ok {
		_spec.SetField(author.FieldCreatedAt, field.TypeTime, value)
	}
	if value, ok := au.mutation.UpdatedAt(); ok {
		_spec.SetField(author.FieldUpdatedAt, field.TypeTime, value)
	}
	if au.mutation.BooksCleared() {
		edge := &sqlgraph.EdgeSpec{
			Rel:     sqlgraph.M2M,
			Inverse: false,
			Table:   author.BooksTable,
			Columns: author.BooksPrimaryKey,
			Bidi:    false,
			Target: &sqlgraph.EdgeTarget{
				IDSpec: sqlgraph.NewFieldSpec(book.FieldID, field.TypeString),
			},
		}
		_spec.Edges.Clear = append(_spec.Edges.Clear, edge)
	}
	if nodes := au.mutation.RemovedBooksIDs(); len(nodes) > 0 && !au.mutation.BooksCleared() {
		edge := &sqlgraph.EdgeSpec{
			Rel:     sqlgraph.M2M,
			Inverse: false,
			Table:   author.BooksTable,
			Columns: author.BooksPrimaryKey,
			Bidi:    false,
			Target: &sqlgraph.EdgeTarget{
				IDSpec: sqlgraph.NewFieldSpec(book.FieldID, field.TypeString),
			},
		}
		for _, k := range nodes {
			edge.Target.Nodes = append(edge.Target.Nodes, k)
		}
		_spec.Edges.Clear = append(_spec.Edges.Clear, edge)
	}
	if nodes := au.mutation.BooksIDs(); len(nodes) > 0 {
		edge := &sqlgraph.EdgeSpec{
			Rel:     sqlgraph.M2M,
			Inverse: false,
			Table:   author.BooksTable,
			Columns: author.BooksPrimaryKey,
			Bidi:    false,
			Target: &sqlgraph.EdgeTarget{
				IDSpec: sqlgraph.NewFieldSpec(book.FieldID, field.TypeString),
			},
		}
		for _, k := range nodes {
			edge.Target.Nodes = append(edge.Target.Nodes, k)
		}
		_spec.Edges.Add = append(_spec.Edges.Add, edge)
	}
	if n, err = sqlgraph.UpdateNodes(ctx, au.driver, _spec); err != nil {
		if _, ok := err.(*sqlgraph.NotFoundError); ok {
			err = &NotFoundError{author.Label}
		} else if sqlgraph.IsConstraintError(err) {
			err = &ConstraintError{msg: err.Error(), wrap: err}
		}
		return 0, err
	}
	au.mutation.done = true
	return n, nil
}

// AuthorUpdateOne is the builder for updating a single Author entity.
type AuthorUpdateOne struct {
	config
	fields   []string
	hooks    []Hook
	mutation *AuthorMutation
}

// SetName sets the "name" field.
func (auo *AuthorUpdateOne) SetName(s string) *AuthorUpdateOne {
	auo.mutation.SetName(s)
	return auo
}

// SetNillableName sets the "name" field if the given value is not nil.
func (auo *AuthorUpdateOne) SetNillableName(s *string) *AuthorUpdateOne {
	if s != nil {
		auo.SetName(*s)
	}
	return auo
}

// SetNameSort sets the "name_sort" field.
func (auo *AuthorUpdateOne) SetNameSort(s string) *AuthorUpdateOne {
	auo.mutation.SetNameSort(s)
	return auo
}

// SetNillableNameSort sets the "name_sort" field if the given value is not nil.
func (auo *AuthorUpdateOne) SetNillableNameSort(s *string) *AuthorUpdateOne {
	if s != nil {
		auo.SetNameSort(*s)
	}
	return auo
}

// SetDescription sets the "description" field.
func (auo *AuthorUpdateOne) SetDescription(s string) *AuthorUpdateOne {
	auo.mutation.SetDescription(s)
	return auo
}

// SetNillableDescription sets the "description" field if the given value is not nil.
func (auo *AuthorUpdateOne) SetNillableDescription(s *string) *AuthorUpdateOne {
	if s != nil {
		auo.SetDescription(*s)
	}
	return auo
}

// ClearDescription clears the value of the "description" field.
func (auo *AuthorUpdateOne) ClearDescription() *AuthorUpdateOne {
	auo.mutation.ClearDescription()
	return auo
}

// SetImagePath sets the "image_path" field.
func (auo *AuthorUpdateOne) SetImagePath(s string) *AuthorUpdateOne {
	auo.mutation.SetImagePath(s)
	return auo
}

// SetNillableImagePath sets the "image_path" field if the given value is not nil.
func (auo *AuthorUpdateOne) SetNillableImagePath(s *string) *AuthorUpdateOne {
	if s != nil {
		auo.SetImagePath(*s)
	}
	return auo
}

// ClearImagePath clears the value of the "image_path" field.
func (auo *AuthorUpdateOne) ClearImagePath() *AuthorUpdateOne {
	auo.mutation.ClearImagePath()
	return auo
}

// SetCreatedAt sets the "created_at" field.
func (auo *AuthorUpdateOne) SetCreatedAt(t time.Time) *AuthorUpdateOne {
	auo.mutation.SetCreatedAt(t)
	return auo
}

// SetNillableCreatedAt sets the "created_at" field if the given value is not nil.
func (auo *AuthorUpdateOne) SetNillableCreatedAt(t *time.Time) *AuthorUpdateOne {
	if t != nil {
		auo.SetCreatedAt(*t)
	}
	return auo
}

// SetUpdatedAt sets the "updated_at" field.
func (auo *AuthorUpdateOne) SetUpdatedAt(t time.Time) *AuthorUpdateOne {
	auo.mutation.SetUpdatedAt(t)
	return auo
}

// SetNillableUpdatedAt sets the "updated_at" field if the given value is not nil.
func (auo *AuthorUpdateOne) SetNillableUpdatedAt(t *time.Time) *AuthorUpdateOne {
	if t != nil {
		auo.SetUpdatedAt(*t)
	}
	return auo
}

// AddBookIDs adds the "books" edge to the Book entity by IDs.
func (auo *AuthorUpdateOne) AddBookIDs(ids ...string) *AuthorUpdateOne {
	auo.mutation.AddBookIDs(ids...)
	return auo
}

// AddBooks adds the "books" edges to the Book entity.
func (auo *AuthorUpdateOne) AddBooks(b ...*Book) *AuthorUpdateOne {
	ids := make([]string, len(b))
	for i := range b {
		ids[i] = b[i].ID
	}
	return auo.AddBookIDs(ids...)
}

// Mutation returns the AuthorMutation object of the builder.
func (auo *AuthorUpdateOne) Mutation() *AuthorMutation {
	return auo.mutation
}

// ClearBooks clears all "books" edges to the Book entity.
func (auo *AuthorUpdateOne) ClearBooks() *AuthorUpdateOne {
	auo.mutation.ClearBooks()
	return auo
}

// RemoveBookIDs removes the "books" edge to Book entities by IDs.
func (auo *AuthorUpdateOne) RemoveBookIDs(ids ...string) *AuthorUpdateOne {
	auo.mutation.RemoveBookIDs(ids...)
	return auo
}

// RemoveBooks removes "books" edges to Book entities.
func (auo *AuthorUpdateOne) RemoveBooks(b ...*Book) *AuthorUpdateOne {
	ids := make([]string, len(b))
	for i := range b {
		ids[i] = b[i].ID
	}
	return auo.RemoveBookIDs(ids...)
}

// Where appends a list predicates to the AuthorUpdate builder.
func (auo *AuthorUpdateOne) Where(ps ...predicate.Author) *AuthorUpdateOne {
	auo.mutation.Where(ps...)
	return auo
}

// Select allows selecting one or more fields (columns) of the returned entity.
// The default is selecting all fields defined in the entity schema.
func (auo *AuthorUpdateOne) Select(field string, fields ...string) *AuthorUpdateOne {
	auo.fields = append([]string{field}, fields...)
	return auo
}

// Save executes the query and returns the updated Author entity.
func (auo *AuthorUpdateOne) Save(ctx context.Context) (*Author, error) {
	return withHooks(ctx, auo.sqlSave, auo.mutation, auo.hooks)
}

// SaveX is like Save, but panics if an error occurs.
func (auo *AuthorUpdateOne) SaveX(ctx context.Context) *Author {
	node, err := auo.Save(ctx)
	if err != nil {
		panic(err)
	}
	return node
}

// Exec executes the query on the entity.
func (auo *AuthorUpdateOne) Exec(ctx context.Context) error {
	_, err := auo.Save(ctx)
	return err
}

// ExecX is like Exec, but panics if an error occurs.
func (auo *AuthorUpdateOne) ExecX(ctx context.Context) {
	if err := auo.Exec(ctx); err != nil {
		panic(err)
	}
}

func (auo *AuthorUpdateOne) sqlSave(ctx context.Context) (_node *Author, err error) {
	_spec := sqlgraph.NewUpdateSpec(author.Table, author.Columns, sqlgraph.NewFieldSpec(author.FieldID, field.TypeString))
	id, ok := auo.mutation.ID()
	if !ok {
		return nil, &ValidationError{Name: "id", err: errors.New(`ent: missing "Author.id" for update`)}
	}
	_spec.Node.ID.Value = id
	if fields := auo.fields; len(fields) > 0 {
		_spec.Node.Columns = make([]string, 0, len(fields))
		_spec.Node.Columns = append(_spec.Node.Columns, author.FieldID)
		for _, f := range fields {
			if !author.ValidColumn(f) {
				return nil, &ValidationError{Name: f, err: fmt.Errorf("ent: invalid field %q for query", f)}
			}
			if f != author.FieldID {
				_spec.Node.Columns = append(_spec.Node.Columns, f)
			}
		}
	}
	if ps := auo.mutation.predicates; len(ps) > 0 {
		_spec.Predicate = func(selector *sql.Selector) {
			for i := range ps {
				ps[i](selector)
			}
		}
	}
	if value, ok := auo.mutation.Name(); ok {
		_spec.SetField(author.FieldName, field.TypeString, value)
	}
	if value, ok := auo.mutation.NameSort(); ok {
		_spec.SetField(author.FieldNameSort, field.TypeString, value)
	}
	if value, ok := auo.mutation.Description(); ok {
		_spec.SetField(author.FieldDescription, field.TypeString, value)
	}
	if auo.mutation.DescriptionCleared() {
		_spec.ClearField(author.FieldDescription, field.TypeString)
	}
	if value, ok := auo.mutation.ImagePath(); ok {
		_spec.SetField(author.FieldImagePath, field.TypeString, value)
	}
	if auo.mutation.ImagePathCleared() {
		_spec.ClearField(author.FieldImagePath, field.TypeString)
	}
	if value, ok := auo.mutation.CreatedAt(); ok {
		_spec.SetField(author.FieldCreatedAt, field.TypeTime, value)
	}
	if value, ok := auo.mutation.UpdatedAt(); ok {
		_spec.SetField(author.FieldUpdatedAt, field.TypeTime, value)
	}
	if auo.mutation.BooksCleared() {
		edge := &sqlgraph.EdgeSpec{
			Rel:     sqlgraph.M2M,
			Inverse: false,
			Table:   author.BooksTable,
			Columns: author.BooksPrimaryKey,
			Bidi:    false,
			Target: &sqlgraph.EdgeTarget{
				IDSpec: sqlgraph.NewFieldSpec(book.FieldID, field.TypeString),
			},
		}
		_spec.Edges.Clear = append(_spec.Edges.Clear, edge)
	}
	if nodes := auo.mutation.RemovedBooksIDs(); len(nodes) > 0 && !auo.mutation.BooksCleared() {
		edge := &sqlgraph.EdgeSpec{
			Rel:     sqlgraph.M2M,
			Inverse: false,
			Table:   author.BooksTable,
			Columns: author.BooksPrimaryKey,
			Bidi:    false,
			Target: &sqlgraph.EdgeTarget{
				IDSpec: sqlgraph.NewFieldSpec(book.FieldID, field.TypeString),
			},
		}
		for _, k := range nodes {
			edge.Target.Nodes = append(edge.Target.Nodes, k)
		}
		_spec.Edges.Clear = append(_spec.Edges.Clear, edge)
	}
	if nodes := auo.mutation.BooksIDs(); len(nodes) > 0 {
		edge := &sqlgraph.EdgeSpec{
			Rel:     sqlgraph.M2M,
			Inverse: false,
			Table:   author.BooksTable,
			Columns: author.BooksPrimaryKey,
			Bidi:    false,
			Target: &sqlgraph.EdgeTarget{
				IDSpec: sqlgraph.NewFieldSpec(book.FieldID, field.TypeString),
			},
		}
		for _, k := range nodes {
			edge.Target.Nodes = append(edge.Target.Nodes, k)
		}
		_spec.Edges.Add = append(_spec.Edges.Add, edge)
	}
	_node = &Author{config: auo.config}
	_spec.Assign = _node.assignValues
	_spec.ScanValues = _node.scanValues
	if err = sqlgraph.UpdateNode(ctx, auo.driver, _spec); err != nil {
		if _, ok := err.(*sqlgraph.NotFoundError); ok {
			err = &NotFoundError{author.Label}
		} else if sqlgraph.IsConstraintError(err) {
			err = &ConstraintError{msg: err.Error(), wrap: err}
		}
		return nil, err
	}
	auo.mutation.done = true
	return _node, nil
}
