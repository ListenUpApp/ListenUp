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
	"github.com/ListenUpApp/ListenUp/internal/ent/book"
	"github.com/ListenUpApp/ListenUp/internal/ent/folder"
	"github.com/ListenUpApp/ListenUp/internal/ent/library"
	"github.com/ListenUpApp/ListenUp/internal/ent/predicate"
)

// FolderUpdate is the builder for updating Folder entities.
type FolderUpdate struct {
	config
	hooks    []Hook
	mutation *FolderMutation
}

// Where appends a list predicates to the FolderUpdate builder.
func (fu *FolderUpdate) Where(ps ...predicate.Folder) *FolderUpdate {
	fu.mutation.Where(ps...)
	return fu
}

// SetName sets the "name" field.
func (fu *FolderUpdate) SetName(s string) *FolderUpdate {
	fu.mutation.SetName(s)
	return fu
}

// SetNillableName sets the "name" field if the given value is not nil.
func (fu *FolderUpdate) SetNillableName(s *string) *FolderUpdate {
	if s != nil {
		fu.SetName(*s)
	}
	return fu
}

// SetPath sets the "path" field.
func (fu *FolderUpdate) SetPath(s string) *FolderUpdate {
	fu.mutation.SetPath(s)
	return fu
}

// SetNillablePath sets the "path" field if the given value is not nil.
func (fu *FolderUpdate) SetNillablePath(s *string) *FolderUpdate {
	if s != nil {
		fu.SetPath(*s)
	}
	return fu
}

// SetLastScannedAt sets the "last_scanned_at" field.
func (fu *FolderUpdate) SetLastScannedAt(t time.Time) *FolderUpdate {
	fu.mutation.SetLastScannedAt(t)
	return fu
}

// SetNillableLastScannedAt sets the "last_scanned_at" field if the given value is not nil.
func (fu *FolderUpdate) SetNillableLastScannedAt(t *time.Time) *FolderUpdate {
	if t != nil {
		fu.SetLastScannedAt(*t)
	}
	return fu
}

// ClearLastScannedAt clears the value of the "last_scanned_at" field.
func (fu *FolderUpdate) ClearLastScannedAt() *FolderUpdate {
	fu.mutation.ClearLastScannedAt()
	return fu
}

// AddLibraryIDs adds the "libraries" edge to the Library entity by IDs.
func (fu *FolderUpdate) AddLibraryIDs(ids ...string) *FolderUpdate {
	fu.mutation.AddLibraryIDs(ids...)
	return fu
}

// AddLibraries adds the "libraries" edges to the Library entity.
func (fu *FolderUpdate) AddLibraries(l ...*Library) *FolderUpdate {
	ids := make([]string, len(l))
	for i := range l {
		ids[i] = l[i].ID
	}
	return fu.AddLibraryIDs(ids...)
}

// AddBookIDs adds the "books" edge to the Book entity by IDs.
func (fu *FolderUpdate) AddBookIDs(ids ...string) *FolderUpdate {
	fu.mutation.AddBookIDs(ids...)
	return fu
}

// AddBooks adds the "books" edges to the Book entity.
func (fu *FolderUpdate) AddBooks(b ...*Book) *FolderUpdate {
	ids := make([]string, len(b))
	for i := range b {
		ids[i] = b[i].ID
	}
	return fu.AddBookIDs(ids...)
}

// Mutation returns the FolderMutation object of the builder.
func (fu *FolderUpdate) Mutation() *FolderMutation {
	return fu.mutation
}

// ClearLibraries clears all "libraries" edges to the Library entity.
func (fu *FolderUpdate) ClearLibraries() *FolderUpdate {
	fu.mutation.ClearLibraries()
	return fu
}

// RemoveLibraryIDs removes the "libraries" edge to Library entities by IDs.
func (fu *FolderUpdate) RemoveLibraryIDs(ids ...string) *FolderUpdate {
	fu.mutation.RemoveLibraryIDs(ids...)
	return fu
}

// RemoveLibraries removes "libraries" edges to Library entities.
func (fu *FolderUpdate) RemoveLibraries(l ...*Library) *FolderUpdate {
	ids := make([]string, len(l))
	for i := range l {
		ids[i] = l[i].ID
	}
	return fu.RemoveLibraryIDs(ids...)
}

// ClearBooks clears all "books" edges to the Book entity.
func (fu *FolderUpdate) ClearBooks() *FolderUpdate {
	fu.mutation.ClearBooks()
	return fu
}

// RemoveBookIDs removes the "books" edge to Book entities by IDs.
func (fu *FolderUpdate) RemoveBookIDs(ids ...string) *FolderUpdate {
	fu.mutation.RemoveBookIDs(ids...)
	return fu
}

// RemoveBooks removes "books" edges to Book entities.
func (fu *FolderUpdate) RemoveBooks(b ...*Book) *FolderUpdate {
	ids := make([]string, len(b))
	for i := range b {
		ids[i] = b[i].ID
	}
	return fu.RemoveBookIDs(ids...)
}

// Save executes the query and returns the number of nodes affected by the update operation.
func (fu *FolderUpdate) Save(ctx context.Context) (int, error) {
	return withHooks(ctx, fu.sqlSave, fu.mutation, fu.hooks)
}

// SaveX is like Save, but panics if an error occurs.
func (fu *FolderUpdate) SaveX(ctx context.Context) int {
	affected, err := fu.Save(ctx)
	if err != nil {
		panic(err)
	}
	return affected
}

// Exec executes the query.
func (fu *FolderUpdate) Exec(ctx context.Context) error {
	_, err := fu.Save(ctx)
	return err
}

// ExecX is like Exec, but panics if an error occurs.
func (fu *FolderUpdate) ExecX(ctx context.Context) {
	if err := fu.Exec(ctx); err != nil {
		panic(err)
	}
}

func (fu *FolderUpdate) sqlSave(ctx context.Context) (n int, err error) {
	_spec := sqlgraph.NewUpdateSpec(folder.Table, folder.Columns, sqlgraph.NewFieldSpec(folder.FieldID, field.TypeString))
	if ps := fu.mutation.predicates; len(ps) > 0 {
		_spec.Predicate = func(selector *sql.Selector) {
			for i := range ps {
				ps[i](selector)
			}
		}
	}
	if value, ok := fu.mutation.Name(); ok {
		_spec.SetField(folder.FieldName, field.TypeString, value)
	}
	if value, ok := fu.mutation.Path(); ok {
		_spec.SetField(folder.FieldPath, field.TypeString, value)
	}
	if value, ok := fu.mutation.LastScannedAt(); ok {
		_spec.SetField(folder.FieldLastScannedAt, field.TypeTime, value)
	}
	if fu.mutation.LastScannedAtCleared() {
		_spec.ClearField(folder.FieldLastScannedAt, field.TypeTime)
	}
	if fu.mutation.LibrariesCleared() {
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
		_spec.Edges.Clear = append(_spec.Edges.Clear, edge)
	}
	if nodes := fu.mutation.RemovedLibrariesIDs(); len(nodes) > 0 && !fu.mutation.LibrariesCleared() {
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
		_spec.Edges.Clear = append(_spec.Edges.Clear, edge)
	}
	if nodes := fu.mutation.LibrariesIDs(); len(nodes) > 0 {
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
		_spec.Edges.Add = append(_spec.Edges.Add, edge)
	}
	if fu.mutation.BooksCleared() {
		edge := &sqlgraph.EdgeSpec{
			Rel:     sqlgraph.O2M,
			Inverse: false,
			Table:   folder.BooksTable,
			Columns: []string{folder.BooksColumn},
			Bidi:    false,
			Target: &sqlgraph.EdgeTarget{
				IDSpec: sqlgraph.NewFieldSpec(book.FieldID, field.TypeString),
			},
		}
		_spec.Edges.Clear = append(_spec.Edges.Clear, edge)
	}
	if nodes := fu.mutation.RemovedBooksIDs(); len(nodes) > 0 && !fu.mutation.BooksCleared() {
		edge := &sqlgraph.EdgeSpec{
			Rel:     sqlgraph.O2M,
			Inverse: false,
			Table:   folder.BooksTable,
			Columns: []string{folder.BooksColumn},
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
	if nodes := fu.mutation.BooksIDs(); len(nodes) > 0 {
		edge := &sqlgraph.EdgeSpec{
			Rel:     sqlgraph.O2M,
			Inverse: false,
			Table:   folder.BooksTable,
			Columns: []string{folder.BooksColumn},
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
	if n, err = sqlgraph.UpdateNodes(ctx, fu.driver, _spec); err != nil {
		if _, ok := err.(*sqlgraph.NotFoundError); ok {
			err = &NotFoundError{folder.Label}
		} else if sqlgraph.IsConstraintError(err) {
			err = &ConstraintError{msg: err.Error(), wrap: err}
		}
		return 0, err
	}
	fu.mutation.done = true
	return n, nil
}

// FolderUpdateOne is the builder for updating a single Folder entity.
type FolderUpdateOne struct {
	config
	fields   []string
	hooks    []Hook
	mutation *FolderMutation
}

// SetName sets the "name" field.
func (fuo *FolderUpdateOne) SetName(s string) *FolderUpdateOne {
	fuo.mutation.SetName(s)
	return fuo
}

// SetNillableName sets the "name" field if the given value is not nil.
func (fuo *FolderUpdateOne) SetNillableName(s *string) *FolderUpdateOne {
	if s != nil {
		fuo.SetName(*s)
	}
	return fuo
}

// SetPath sets the "path" field.
func (fuo *FolderUpdateOne) SetPath(s string) *FolderUpdateOne {
	fuo.mutation.SetPath(s)
	return fuo
}

// SetNillablePath sets the "path" field if the given value is not nil.
func (fuo *FolderUpdateOne) SetNillablePath(s *string) *FolderUpdateOne {
	if s != nil {
		fuo.SetPath(*s)
	}
	return fuo
}

// SetLastScannedAt sets the "last_scanned_at" field.
func (fuo *FolderUpdateOne) SetLastScannedAt(t time.Time) *FolderUpdateOne {
	fuo.mutation.SetLastScannedAt(t)
	return fuo
}

// SetNillableLastScannedAt sets the "last_scanned_at" field if the given value is not nil.
func (fuo *FolderUpdateOne) SetNillableLastScannedAt(t *time.Time) *FolderUpdateOne {
	if t != nil {
		fuo.SetLastScannedAt(*t)
	}
	return fuo
}

// ClearLastScannedAt clears the value of the "last_scanned_at" field.
func (fuo *FolderUpdateOne) ClearLastScannedAt() *FolderUpdateOne {
	fuo.mutation.ClearLastScannedAt()
	return fuo
}

// AddLibraryIDs adds the "libraries" edge to the Library entity by IDs.
func (fuo *FolderUpdateOne) AddLibraryIDs(ids ...string) *FolderUpdateOne {
	fuo.mutation.AddLibraryIDs(ids...)
	return fuo
}

// AddLibraries adds the "libraries" edges to the Library entity.
func (fuo *FolderUpdateOne) AddLibraries(l ...*Library) *FolderUpdateOne {
	ids := make([]string, len(l))
	for i := range l {
		ids[i] = l[i].ID
	}
	return fuo.AddLibraryIDs(ids...)
}

// AddBookIDs adds the "books" edge to the Book entity by IDs.
func (fuo *FolderUpdateOne) AddBookIDs(ids ...string) *FolderUpdateOne {
	fuo.mutation.AddBookIDs(ids...)
	return fuo
}

// AddBooks adds the "books" edges to the Book entity.
func (fuo *FolderUpdateOne) AddBooks(b ...*Book) *FolderUpdateOne {
	ids := make([]string, len(b))
	for i := range b {
		ids[i] = b[i].ID
	}
	return fuo.AddBookIDs(ids...)
}

// Mutation returns the FolderMutation object of the builder.
func (fuo *FolderUpdateOne) Mutation() *FolderMutation {
	return fuo.mutation
}

// ClearLibraries clears all "libraries" edges to the Library entity.
func (fuo *FolderUpdateOne) ClearLibraries() *FolderUpdateOne {
	fuo.mutation.ClearLibraries()
	return fuo
}

// RemoveLibraryIDs removes the "libraries" edge to Library entities by IDs.
func (fuo *FolderUpdateOne) RemoveLibraryIDs(ids ...string) *FolderUpdateOne {
	fuo.mutation.RemoveLibraryIDs(ids...)
	return fuo
}

// RemoveLibraries removes "libraries" edges to Library entities.
func (fuo *FolderUpdateOne) RemoveLibraries(l ...*Library) *FolderUpdateOne {
	ids := make([]string, len(l))
	for i := range l {
		ids[i] = l[i].ID
	}
	return fuo.RemoveLibraryIDs(ids...)
}

// ClearBooks clears all "books" edges to the Book entity.
func (fuo *FolderUpdateOne) ClearBooks() *FolderUpdateOne {
	fuo.mutation.ClearBooks()
	return fuo
}

// RemoveBookIDs removes the "books" edge to Book entities by IDs.
func (fuo *FolderUpdateOne) RemoveBookIDs(ids ...string) *FolderUpdateOne {
	fuo.mutation.RemoveBookIDs(ids...)
	return fuo
}

// RemoveBooks removes "books" edges to Book entities.
func (fuo *FolderUpdateOne) RemoveBooks(b ...*Book) *FolderUpdateOne {
	ids := make([]string, len(b))
	for i := range b {
		ids[i] = b[i].ID
	}
	return fuo.RemoveBookIDs(ids...)
}

// Where appends a list predicates to the FolderUpdate builder.
func (fuo *FolderUpdateOne) Where(ps ...predicate.Folder) *FolderUpdateOne {
	fuo.mutation.Where(ps...)
	return fuo
}

// Select allows selecting one or more fields (columns) of the returned entity.
// The default is selecting all fields defined in the entity schema.
func (fuo *FolderUpdateOne) Select(field string, fields ...string) *FolderUpdateOne {
	fuo.fields = append([]string{field}, fields...)
	return fuo
}

// Save executes the query and returns the updated Folder entity.
func (fuo *FolderUpdateOne) Save(ctx context.Context) (*Folder, error) {
	return withHooks(ctx, fuo.sqlSave, fuo.mutation, fuo.hooks)
}

// SaveX is like Save, but panics if an error occurs.
func (fuo *FolderUpdateOne) SaveX(ctx context.Context) *Folder {
	node, err := fuo.Save(ctx)
	if err != nil {
		panic(err)
	}
	return node
}

// Exec executes the query on the entity.
func (fuo *FolderUpdateOne) Exec(ctx context.Context) error {
	_, err := fuo.Save(ctx)
	return err
}

// ExecX is like Exec, but panics if an error occurs.
func (fuo *FolderUpdateOne) ExecX(ctx context.Context) {
	if err := fuo.Exec(ctx); err != nil {
		panic(err)
	}
}

func (fuo *FolderUpdateOne) sqlSave(ctx context.Context) (_node *Folder, err error) {
	_spec := sqlgraph.NewUpdateSpec(folder.Table, folder.Columns, sqlgraph.NewFieldSpec(folder.FieldID, field.TypeString))
	id, ok := fuo.mutation.ID()
	if !ok {
		return nil, &ValidationError{Name: "id", err: errors.New(`ent: missing "Folder.id" for update`)}
	}
	_spec.Node.ID.Value = id
	if fields := fuo.fields; len(fields) > 0 {
		_spec.Node.Columns = make([]string, 0, len(fields))
		_spec.Node.Columns = append(_spec.Node.Columns, folder.FieldID)
		for _, f := range fields {
			if !folder.ValidColumn(f) {
				return nil, &ValidationError{Name: f, err: fmt.Errorf("ent: invalid field %q for query", f)}
			}
			if f != folder.FieldID {
				_spec.Node.Columns = append(_spec.Node.Columns, f)
			}
		}
	}
	if ps := fuo.mutation.predicates; len(ps) > 0 {
		_spec.Predicate = func(selector *sql.Selector) {
			for i := range ps {
				ps[i](selector)
			}
		}
	}
	if value, ok := fuo.mutation.Name(); ok {
		_spec.SetField(folder.FieldName, field.TypeString, value)
	}
	if value, ok := fuo.mutation.Path(); ok {
		_spec.SetField(folder.FieldPath, field.TypeString, value)
	}
	if value, ok := fuo.mutation.LastScannedAt(); ok {
		_spec.SetField(folder.FieldLastScannedAt, field.TypeTime, value)
	}
	if fuo.mutation.LastScannedAtCleared() {
		_spec.ClearField(folder.FieldLastScannedAt, field.TypeTime)
	}
	if fuo.mutation.LibrariesCleared() {
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
		_spec.Edges.Clear = append(_spec.Edges.Clear, edge)
	}
	if nodes := fuo.mutation.RemovedLibrariesIDs(); len(nodes) > 0 && !fuo.mutation.LibrariesCleared() {
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
		_spec.Edges.Clear = append(_spec.Edges.Clear, edge)
	}
	if nodes := fuo.mutation.LibrariesIDs(); len(nodes) > 0 {
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
		_spec.Edges.Add = append(_spec.Edges.Add, edge)
	}
	if fuo.mutation.BooksCleared() {
		edge := &sqlgraph.EdgeSpec{
			Rel:     sqlgraph.O2M,
			Inverse: false,
			Table:   folder.BooksTable,
			Columns: []string{folder.BooksColumn},
			Bidi:    false,
			Target: &sqlgraph.EdgeTarget{
				IDSpec: sqlgraph.NewFieldSpec(book.FieldID, field.TypeString),
			},
		}
		_spec.Edges.Clear = append(_spec.Edges.Clear, edge)
	}
	if nodes := fuo.mutation.RemovedBooksIDs(); len(nodes) > 0 && !fuo.mutation.BooksCleared() {
		edge := &sqlgraph.EdgeSpec{
			Rel:     sqlgraph.O2M,
			Inverse: false,
			Table:   folder.BooksTable,
			Columns: []string{folder.BooksColumn},
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
	if nodes := fuo.mutation.BooksIDs(); len(nodes) > 0 {
		edge := &sqlgraph.EdgeSpec{
			Rel:     sqlgraph.O2M,
			Inverse: false,
			Table:   folder.BooksTable,
			Columns: []string{folder.BooksColumn},
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
	_node = &Folder{config: fuo.config}
	_spec.Assign = _node.assignValues
	_spec.ScanValues = _node.scanValues
	if err = sqlgraph.UpdateNode(ctx, fuo.driver, _spec); err != nil {
		if _, ok := err.(*sqlgraph.NotFoundError); ok {
			err = &NotFoundError{folder.Label}
		} else if sqlgraph.IsConstraintError(err) {
			err = &ConstraintError{msg: err.Error(), wrap: err}
		}
		return nil, err
	}
	fuo.mutation.done = true
	return _node, nil
}
