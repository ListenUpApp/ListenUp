package schema

import (
	"entgo.io/ent"
	"entgo.io/ent/schema/edge"
	"entgo.io/ent/schema/field"
	"github.com/ListenUpApp/ListenUp/internal/util"
)

// Fields of the Library
func (Folder) Fields() []ent.Field {
	return []ent.Field{
		field.String("id").
			DefaultFunc(util.NewID).
			Unique().
			Immutable().
			Comment("Unique identifier for the folder"),
		field.String("name").
			Unique().
			Comment("The folder's name"),
		field.String("path").
			Comment("The folders' path"),
		field.Time("last_scanned_at").
			Optional().
			Comment(("Time when the folder was last scanned")),
	}
}

// Edges of the Library.
func (Folder) Edges() []ent.Edge {
	return []ent.Edge{
		edge.From("libraries", Library.Type).
			Ref("folders"),
		edge.To("books", Book.Type),
	}
}
