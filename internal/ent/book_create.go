// Code generated by ent, DO NOT EDIT.

package ent

import (
	"context"
	"errors"
	"fmt"
	"time"

	"entgo.io/ent/dialect/sql/sqlgraph"
	"entgo.io/ent/schema/field"
	"github.com/ListenUpApp/ListenUp/internal/ent/author"
	"github.com/ListenUpApp/ListenUp/internal/ent/book"
	"github.com/ListenUpApp/ListenUp/internal/ent/bookcover"
	"github.com/ListenUpApp/ListenUp/internal/ent/chapter"
	"github.com/ListenUpApp/ListenUp/internal/ent/folder"
	"github.com/ListenUpApp/ListenUp/internal/ent/library"
	"github.com/ListenUpApp/ListenUp/internal/ent/narrator"
)

// BookCreate is the builder for creating a Book entity.
type BookCreate struct {
	config
	mutation *BookMutation
	hooks    []Hook
}

// SetDuration sets the "duration" field.
func (bc *BookCreate) SetDuration(f float64) *BookCreate {
	bc.mutation.SetDuration(f)
	return bc
}

// SetSize sets the "size" field.
func (bc *BookCreate) SetSize(i int64) *BookCreate {
	bc.mutation.SetSize(i)
	return bc
}

// SetTitle sets the "title" field.
func (bc *BookCreate) SetTitle(s string) *BookCreate {
	bc.mutation.SetTitle(s)
	return bc
}

// SetSubtitle sets the "subtitle" field.
func (bc *BookCreate) SetSubtitle(s string) *BookCreate {
	bc.mutation.SetSubtitle(s)
	return bc
}

// SetNillableSubtitle sets the "subtitle" field if the given value is not nil.
func (bc *BookCreate) SetNillableSubtitle(s *string) *BookCreate {
	if s != nil {
		bc.SetSubtitle(*s)
	}
	return bc
}

// SetDescription sets the "description" field.
func (bc *BookCreate) SetDescription(s string) *BookCreate {
	bc.mutation.SetDescription(s)
	return bc
}

// SetNillableDescription sets the "description" field if the given value is not nil.
func (bc *BookCreate) SetNillableDescription(s *string) *BookCreate {
	if s != nil {
		bc.SetDescription(*s)
	}
	return bc
}

// SetIsbn sets the "isbn" field.
func (bc *BookCreate) SetIsbn(s string) *BookCreate {
	bc.mutation.SetIsbn(s)
	return bc
}

// SetNillableIsbn sets the "isbn" field if the given value is not nil.
func (bc *BookCreate) SetNillableIsbn(s *string) *BookCreate {
	if s != nil {
		bc.SetIsbn(*s)
	}
	return bc
}

// SetAsin sets the "asin" field.
func (bc *BookCreate) SetAsin(s string) *BookCreate {
	bc.mutation.SetAsin(s)
	return bc
}

// SetNillableAsin sets the "asin" field if the given value is not nil.
func (bc *BookCreate) SetNillableAsin(s *string) *BookCreate {
	if s != nil {
		bc.SetAsin(*s)
	}
	return bc
}

// SetLanguage sets the "language" field.
func (bc *BookCreate) SetLanguage(s string) *BookCreate {
	bc.mutation.SetLanguage(s)
	return bc
}

// SetNillableLanguage sets the "language" field if the given value is not nil.
func (bc *BookCreate) SetNillableLanguage(s *string) *BookCreate {
	if s != nil {
		bc.SetLanguage(*s)
	}
	return bc
}

// SetExplicit sets the "explicit" field.
func (bc *BookCreate) SetExplicit(b bool) *BookCreate {
	bc.mutation.SetExplicit(b)
	return bc
}

// SetNillableExplicit sets the "explicit" field if the given value is not nil.
func (bc *BookCreate) SetNillableExplicit(b *bool) *BookCreate {
	if b != nil {
		bc.SetExplicit(*b)
	}
	return bc
}

// SetPublisher sets the "publisher" field.
func (bc *BookCreate) SetPublisher(s string) *BookCreate {
	bc.mutation.SetPublisher(s)
	return bc
}

// SetNillablePublisher sets the "publisher" field if the given value is not nil.
func (bc *BookCreate) SetNillablePublisher(s *string) *BookCreate {
	if s != nil {
		bc.SetPublisher(*s)
	}
	return bc
}

// SetPublishedDate sets the "published_date" field.
func (bc *BookCreate) SetPublishedDate(t time.Time) *BookCreate {
	bc.mutation.SetPublishedDate(t)
	return bc
}

// SetNillablePublishedDate sets the "published_date" field if the given value is not nil.
func (bc *BookCreate) SetNillablePublishedDate(t *time.Time) *BookCreate {
	if t != nil {
		bc.SetPublishedDate(*t)
	}
	return bc
}

// SetGenres sets the "genres" field.
func (bc *BookCreate) SetGenres(s []string) *BookCreate {
	bc.mutation.SetGenres(s)
	return bc
}

// SetTags sets the "tags" field.
func (bc *BookCreate) SetTags(s []string) *BookCreate {
	bc.mutation.SetTags(s)
	return bc
}

// SetCreatedAt sets the "created_at" field.
func (bc *BookCreate) SetCreatedAt(t time.Time) *BookCreate {
	bc.mutation.SetCreatedAt(t)
	return bc
}

// SetNillableCreatedAt sets the "created_at" field if the given value is not nil.
func (bc *BookCreate) SetNillableCreatedAt(t *time.Time) *BookCreate {
	if t != nil {
		bc.SetCreatedAt(*t)
	}
	return bc
}

// SetUpdatedAt sets the "updated_at" field.
func (bc *BookCreate) SetUpdatedAt(t time.Time) *BookCreate {
	bc.mutation.SetUpdatedAt(t)
	return bc
}

// SetNillableUpdatedAt sets the "updated_at" field if the given value is not nil.
func (bc *BookCreate) SetNillableUpdatedAt(t *time.Time) *BookCreate {
	if t != nil {
		bc.SetUpdatedAt(*t)
	}
	return bc
}

// SetID sets the "id" field.
func (bc *BookCreate) SetID(s string) *BookCreate {
	bc.mutation.SetID(s)
	return bc
}

// SetNillableID sets the "id" field if the given value is not nil.
func (bc *BookCreate) SetNillableID(s *string) *BookCreate {
	if s != nil {
		bc.SetID(*s)
	}
	return bc
}

// AddChapterIDs adds the "chapters" edge to the Chapter entity by IDs.
func (bc *BookCreate) AddChapterIDs(ids ...int) *BookCreate {
	bc.mutation.AddChapterIDs(ids...)
	return bc
}

// AddChapters adds the "chapters" edges to the Chapter entity.
func (bc *BookCreate) AddChapters(c ...*Chapter) *BookCreate {
	ids := make([]int, len(c))
	for i := range c {
		ids[i] = c[i].ID
	}
	return bc.AddChapterIDs(ids...)
}

// SetCoverID sets the "cover" edge to the BookCover entity by ID.
func (bc *BookCreate) SetCoverID(id int) *BookCreate {
	bc.mutation.SetCoverID(id)
	return bc
}

// SetNillableCoverID sets the "cover" edge to the BookCover entity by ID if the given value is not nil.
func (bc *BookCreate) SetNillableCoverID(id *int) *BookCreate {
	if id != nil {
		bc = bc.SetCoverID(*id)
	}
	return bc
}

// SetCover sets the "cover" edge to the BookCover entity.
func (bc *BookCreate) SetCover(b *BookCover) *BookCreate {
	return bc.SetCoverID(b.ID)
}

// AddAuthorIDs adds the "authors" edge to the Author entity by IDs.
func (bc *BookCreate) AddAuthorIDs(ids ...string) *BookCreate {
	bc.mutation.AddAuthorIDs(ids...)
	return bc
}

// AddAuthors adds the "authors" edges to the Author entity.
func (bc *BookCreate) AddAuthors(a ...*Author) *BookCreate {
	ids := make([]string, len(a))
	for i := range a {
		ids[i] = a[i].ID
	}
	return bc.AddAuthorIDs(ids...)
}

// AddNarratorIDs adds the "narrators" edge to the Narrator entity by IDs.
func (bc *BookCreate) AddNarratorIDs(ids ...string) *BookCreate {
	bc.mutation.AddNarratorIDs(ids...)
	return bc
}

// AddNarrators adds the "narrators" edges to the Narrator entity.
func (bc *BookCreate) AddNarrators(n ...*Narrator) *BookCreate {
	ids := make([]string, len(n))
	for i := range n {
		ids[i] = n[i].ID
	}
	return bc.AddNarratorIDs(ids...)
}

// SetLibraryID sets the "library" edge to the Library entity by ID.
func (bc *BookCreate) SetLibraryID(id string) *BookCreate {
	bc.mutation.SetLibraryID(id)
	return bc
}

// SetLibrary sets the "library" edge to the Library entity.
func (bc *BookCreate) SetLibrary(l *Library) *BookCreate {
	return bc.SetLibraryID(l.ID)
}

// SetFolderID sets the "folder" edge to the Folder entity by ID.
func (bc *BookCreate) SetFolderID(id string) *BookCreate {
	bc.mutation.SetFolderID(id)
	return bc
}

// SetFolder sets the "folder" edge to the Folder entity.
func (bc *BookCreate) SetFolder(f *Folder) *BookCreate {
	return bc.SetFolderID(f.ID)
}

// Mutation returns the BookMutation object of the builder.
func (bc *BookCreate) Mutation() *BookMutation {
	return bc.mutation
}

// Save creates the Book in the database.
func (bc *BookCreate) Save(ctx context.Context) (*Book, error) {
	bc.defaults()
	return withHooks(ctx, bc.sqlSave, bc.mutation, bc.hooks)
}

// SaveX calls Save and panics if Save returns an error.
func (bc *BookCreate) SaveX(ctx context.Context) *Book {
	v, err := bc.Save(ctx)
	if err != nil {
		panic(err)
	}
	return v
}

// Exec executes the query.
func (bc *BookCreate) Exec(ctx context.Context) error {
	_, err := bc.Save(ctx)
	return err
}

// ExecX is like Exec, but panics if an error occurs.
func (bc *BookCreate) ExecX(ctx context.Context) {
	if err := bc.Exec(ctx); err != nil {
		panic(err)
	}
}

// defaults sets the default values of the builder before save.
func (bc *BookCreate) defaults() {
	if _, ok := bc.mutation.Explicit(); !ok {
		v := book.DefaultExplicit
		bc.mutation.SetExplicit(v)
	}
	if _, ok := bc.mutation.CreatedAt(); !ok {
		v := book.DefaultCreatedAt()
		bc.mutation.SetCreatedAt(v)
	}
	if _, ok := bc.mutation.UpdatedAt(); !ok {
		v := book.DefaultUpdatedAt()
		bc.mutation.SetUpdatedAt(v)
	}
	if _, ok := bc.mutation.ID(); !ok {
		v := book.DefaultID()
		bc.mutation.SetID(v)
	}
}

// check runs all checks and user-defined validators on the builder.
func (bc *BookCreate) check() error {
	if _, ok := bc.mutation.Duration(); !ok {
		return &ValidationError{Name: "duration", err: errors.New(`ent: missing required field "Book.duration"`)}
	}
	if _, ok := bc.mutation.Size(); !ok {
		return &ValidationError{Name: "size", err: errors.New(`ent: missing required field "Book.size"`)}
	}
	if _, ok := bc.mutation.Title(); !ok {
		return &ValidationError{Name: "title", err: errors.New(`ent: missing required field "Book.title"`)}
	}
	if _, ok := bc.mutation.Explicit(); !ok {
		return &ValidationError{Name: "explicit", err: errors.New(`ent: missing required field "Book.explicit"`)}
	}
	if _, ok := bc.mutation.CreatedAt(); !ok {
		return &ValidationError{Name: "created_at", err: errors.New(`ent: missing required field "Book.created_at"`)}
	}
	if _, ok := bc.mutation.UpdatedAt(); !ok {
		return &ValidationError{Name: "updated_at", err: errors.New(`ent: missing required field "Book.updated_at"`)}
	}
	if len(bc.mutation.LibraryIDs()) == 0 {
		return &ValidationError{Name: "library", err: errors.New(`ent: missing required edge "Book.library"`)}
	}
	if len(bc.mutation.FolderIDs()) == 0 {
		return &ValidationError{Name: "folder", err: errors.New(`ent: missing required edge "Book.folder"`)}
	}
	return nil
}

func (bc *BookCreate) sqlSave(ctx context.Context) (*Book, error) {
	if err := bc.check(); err != nil {
		return nil, err
	}
	_node, _spec := bc.createSpec()
	if err := sqlgraph.CreateNode(ctx, bc.driver, _spec); err != nil {
		if sqlgraph.IsConstraintError(err) {
			err = &ConstraintError{msg: err.Error(), wrap: err}
		}
		return nil, err
	}
	if _spec.ID.Value != nil {
		if id, ok := _spec.ID.Value.(string); ok {
			_node.ID = id
		} else {
			return nil, fmt.Errorf("unexpected Book.ID type: %T", _spec.ID.Value)
		}
	}
	bc.mutation.id = &_node.ID
	bc.mutation.done = true
	return _node, nil
}

func (bc *BookCreate) createSpec() (*Book, *sqlgraph.CreateSpec) {
	var (
		_node = &Book{config: bc.config}
		_spec = sqlgraph.NewCreateSpec(book.Table, sqlgraph.NewFieldSpec(book.FieldID, field.TypeString))
	)
	if id, ok := bc.mutation.ID(); ok {
		_node.ID = id
		_spec.ID.Value = id
	}
	if value, ok := bc.mutation.Duration(); ok {
		_spec.SetField(book.FieldDuration, field.TypeFloat64, value)
		_node.Duration = value
	}
	if value, ok := bc.mutation.Size(); ok {
		_spec.SetField(book.FieldSize, field.TypeInt64, value)
		_node.Size = value
	}
	if value, ok := bc.mutation.Title(); ok {
		_spec.SetField(book.FieldTitle, field.TypeString, value)
		_node.Title = value
	}
	if value, ok := bc.mutation.Subtitle(); ok {
		_spec.SetField(book.FieldSubtitle, field.TypeString, value)
		_node.Subtitle = value
	}
	if value, ok := bc.mutation.Description(); ok {
		_spec.SetField(book.FieldDescription, field.TypeString, value)
		_node.Description = value
	}
	if value, ok := bc.mutation.Isbn(); ok {
		_spec.SetField(book.FieldIsbn, field.TypeString, value)
		_node.Isbn = value
	}
	if value, ok := bc.mutation.Asin(); ok {
		_spec.SetField(book.FieldAsin, field.TypeString, value)
		_node.Asin = value
	}
	if value, ok := bc.mutation.Language(); ok {
		_spec.SetField(book.FieldLanguage, field.TypeString, value)
		_node.Language = value
	}
	if value, ok := bc.mutation.Explicit(); ok {
		_spec.SetField(book.FieldExplicit, field.TypeBool, value)
		_node.Explicit = value
	}
	if value, ok := bc.mutation.Publisher(); ok {
		_spec.SetField(book.FieldPublisher, field.TypeString, value)
		_node.Publisher = value
	}
	if value, ok := bc.mutation.PublishedDate(); ok {
		_spec.SetField(book.FieldPublishedDate, field.TypeTime, value)
		_node.PublishedDate = value
	}
	if value, ok := bc.mutation.Genres(); ok {
		_spec.SetField(book.FieldGenres, field.TypeJSON, value)
		_node.Genres = value
	}
	if value, ok := bc.mutation.Tags(); ok {
		_spec.SetField(book.FieldTags, field.TypeJSON, value)
		_node.Tags = value
	}
	if value, ok := bc.mutation.CreatedAt(); ok {
		_spec.SetField(book.FieldCreatedAt, field.TypeTime, value)
		_node.CreatedAt = value
	}
	if value, ok := bc.mutation.UpdatedAt(); ok {
		_spec.SetField(book.FieldUpdatedAt, field.TypeTime, value)
		_node.UpdatedAt = value
	}
	if nodes := bc.mutation.ChaptersIDs(); len(nodes) > 0 {
		edge := &sqlgraph.EdgeSpec{
			Rel:     sqlgraph.O2M,
			Inverse: false,
			Table:   book.ChaptersTable,
			Columns: []string{book.ChaptersColumn},
			Bidi:    false,
			Target: &sqlgraph.EdgeTarget{
				IDSpec: sqlgraph.NewFieldSpec(chapter.FieldID, field.TypeInt),
			},
		}
		for _, k := range nodes {
			edge.Target.Nodes = append(edge.Target.Nodes, k)
		}
		_spec.Edges = append(_spec.Edges, edge)
	}
	if nodes := bc.mutation.CoverIDs(); len(nodes) > 0 {
		edge := &sqlgraph.EdgeSpec{
			Rel:     sqlgraph.O2O,
			Inverse: false,
			Table:   book.CoverTable,
			Columns: []string{book.CoverColumn},
			Bidi:    false,
			Target: &sqlgraph.EdgeTarget{
				IDSpec: sqlgraph.NewFieldSpec(bookcover.FieldID, field.TypeInt),
			},
		}
		for _, k := range nodes {
			edge.Target.Nodes = append(edge.Target.Nodes, k)
		}
		_spec.Edges = append(_spec.Edges, edge)
	}
	if nodes := bc.mutation.AuthorsIDs(); len(nodes) > 0 {
		edge := &sqlgraph.EdgeSpec{
			Rel:     sqlgraph.M2M,
			Inverse: true,
			Table:   book.AuthorsTable,
			Columns: book.AuthorsPrimaryKey,
			Bidi:    false,
			Target: &sqlgraph.EdgeTarget{
				IDSpec: sqlgraph.NewFieldSpec(author.FieldID, field.TypeString),
			},
		}
		for _, k := range nodes {
			edge.Target.Nodes = append(edge.Target.Nodes, k)
		}
		_spec.Edges = append(_spec.Edges, edge)
	}
	if nodes := bc.mutation.NarratorsIDs(); len(nodes) > 0 {
		edge := &sqlgraph.EdgeSpec{
			Rel:     sqlgraph.M2M,
			Inverse: true,
			Table:   book.NarratorsTable,
			Columns: book.NarratorsPrimaryKey,
			Bidi:    false,
			Target: &sqlgraph.EdgeTarget{
				IDSpec: sqlgraph.NewFieldSpec(narrator.FieldID, field.TypeString),
			},
		}
		for _, k := range nodes {
			edge.Target.Nodes = append(edge.Target.Nodes, k)
		}
		_spec.Edges = append(_spec.Edges, edge)
	}
	if nodes := bc.mutation.LibraryIDs(); len(nodes) > 0 {
		edge := &sqlgraph.EdgeSpec{
			Rel:     sqlgraph.M2O,
			Inverse: true,
			Table:   book.LibraryTable,
			Columns: []string{book.LibraryColumn},
			Bidi:    false,
			Target: &sqlgraph.EdgeTarget{
				IDSpec: sqlgraph.NewFieldSpec(library.FieldID, field.TypeString),
			},
		}
		for _, k := range nodes {
			edge.Target.Nodes = append(edge.Target.Nodes, k)
		}
		_node.library_library_books = &nodes[0]
		_spec.Edges = append(_spec.Edges, edge)
	}
	if nodes := bc.mutation.FolderIDs(); len(nodes) > 0 {
		edge := &sqlgraph.EdgeSpec{
			Rel:     sqlgraph.M2O,
			Inverse: true,
			Table:   book.FolderTable,
			Columns: []string{book.FolderColumn},
			Bidi:    false,
			Target: &sqlgraph.EdgeTarget{
				IDSpec: sqlgraph.NewFieldSpec(folder.FieldID, field.TypeString),
			},
		}
		for _, k := range nodes {
			edge.Target.Nodes = append(edge.Target.Nodes, k)
		}
		_node.folder_books = &nodes[0]
		_spec.Edges = append(_spec.Edges, edge)
	}
	return _node, _spec
}

// BookCreateBulk is the builder for creating many Book entities in bulk.
type BookCreateBulk struct {
	config
	err      error
	builders []*BookCreate
}

// Save creates the Book entities in the database.
func (bcb *BookCreateBulk) Save(ctx context.Context) ([]*Book, error) {
	if bcb.err != nil {
		return nil, bcb.err
	}
	specs := make([]*sqlgraph.CreateSpec, len(bcb.builders))
	nodes := make([]*Book, len(bcb.builders))
	mutators := make([]Mutator, len(bcb.builders))
	for i := range bcb.builders {
		func(i int, root context.Context) {
			builder := bcb.builders[i]
			builder.defaults()
			var mut Mutator = MutateFunc(func(ctx context.Context, m Mutation) (Value, error) {
				mutation, ok := m.(*BookMutation)
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
					_, err = mutators[i+1].Mutate(root, bcb.builders[i+1].mutation)
				} else {
					spec := &sqlgraph.BatchCreateSpec{Nodes: specs}
					// Invoke the actual operation on the latest mutation in the chain.
					if err = sqlgraph.BatchCreate(ctx, bcb.driver, spec); err != nil {
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
		if _, err := mutators[0].Mutate(ctx, bcb.builders[0].mutation); err != nil {
			return nil, err
		}
	}
	return nodes, nil
}

// SaveX is like Save, but panics if an error occurs.
func (bcb *BookCreateBulk) SaveX(ctx context.Context) []*Book {
	v, err := bcb.Save(ctx)
	if err != nil {
		panic(err)
	}
	return v
}

// Exec executes the query.
func (bcb *BookCreateBulk) Exec(ctx context.Context) error {
	_, err := bcb.Save(ctx)
	return err
}

// ExecX is like Exec, but panics if an error occurs.
func (bcb *BookCreateBulk) ExecX(ctx context.Context) {
	if err := bcb.Exec(ctx); err != nil {
		panic(err)
	}
}
