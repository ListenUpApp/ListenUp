package schema

import (
	"entgo.io/ent"
	"entgo.io/ent/schema/edge"
	"entgo.io/ent/schema/field"
	"entgo.io/ent/schema/index"
	"github.com/ListenUpApp/ListenUp/internal/util"
)

// Book holds the schema definition for the Book entity.
type Book struct {
	ent.Schema
}

// Fields of the Book.
func (Book) Fields() []ent.Field {
	return []ent.Field{
		field.String("id").
		DefaultFunc(util.NewID).
		Unique().
		Immutable(),
		field.Float("duration"),
		field.Int64("size"),
		field.Time("created_at"),
		field.Time("updated_at"),
		field.Time("imported_at"),
		field.String("title"),
		field.String("title_sort"),
		field.String("subtitle").Optional(),
		field.String("description").Optional(),
		field.String("isbn").Optional(),
		field.String("asin").Optional(),
		field.String("language").Optional(),
		field.Bool("explicit").Default(false),
		field.String("publisher").Optional(),
		field.Time("published_date").Optional(),
		field.Strings("genres").Optional(),
		field.Strings("tags").Optional(),
	}
}

// Edges of the Book.
func (Book) Edges() []ent.Edge {
	return []ent.Edge{
		edge.To("chapters", Chapter.Type),
		edge.To("cover", BookCover.Type).Unique(),
		edge.From("authors", Author.Type).Ref("books"),
		edge.From("narrators", Narrator.Type).Ref("books"),
		edge.From("library", Library.Type).
			Ref("library_books").
			Unique().
			Required(),
		edge.From("folder", Folder.Type).
			Ref("books").
			Unique().
			Required(),
	}
}

// Indexes
func (Book) Indexes() []ent.Index {
	return []ent.Index{
		index.Fields("title_sort"),
	}
}
