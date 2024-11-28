package schema

import (
	"entgo.io/ent"
	"entgo.io/ent/schema/edge"
	"entgo.io/ent/schema/field"
	"entgo.io/ent/schema/index"
	"github.com/ListenUpApp/ListenUp/internal/util"
)

// Library holds the schema definition for the Library entity.
type Library struct {
	ent.Schema
}

// Fields of the Library
func (Library) Fields() []ent.Field {
	return []ent.Field{
		field.String("id").
			DefaultFunc(util.NewID).
			Unique().
			Immutable().
			Comment("Unique identifier for the library"),
		field.String("name").
			Comment("The libraries name"),
	}
}

// Edges of the Library.
func (Library) Edges() []ent.Edge {
	return []ent.Edge{
		edge.From("users", User.Type).
			Ref("libraries"),
		edge.From("active_users", User.Type).
			Ref("active_library"),
		edge.To("folders", Folder.Type),
		edge.To("library_books", Book.Type),
	}
}

func (Library) Indexes() []ent.Index {
	return []ent.Index{
		index.Fields("name"),
	}
}

// Folder holds the schema definition for the Folder entity.
type Folder struct {
	ent.Schema
}
