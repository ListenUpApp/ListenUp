package schema

import (
	"entgo.io/ent"
	"entgo.io/ent/schema/edge"
	"entgo.io/ent/schema/field"
	"entgo.io/ent/schema/index"
	"github.com/ListenUpApp/ListenUp/internal/util"
)

// Author holds the schema definition for the Author entity.
type Author struct {
	ent.Schema
}

// Fields of the Author.
func (Author) Fields() []ent.Field {
	return []ent.Field{
		field.String("id").
			DefaultFunc(util.NewID).
			Unique().
			Immutable(),
		field.String("name"),
		field.String("name_sort"),
		field.String("description").Optional(),
		field.String("image_path").Optional(),
		field.Time("created_at"),
		field.Time("updated_at"),
	}
}

// Edges of the Author.
func (Author) Edges() []ent.Edge {
	return []ent.Edge{
		edge.To("books", Book.Type),
	}
}

func (Author) Indexes() []ent.Index {
	return []ent.Index{
		index.Fields("name_sort"),
	}
}
