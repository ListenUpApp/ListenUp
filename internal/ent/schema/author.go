package schema

import (
	"time"

	"entgo.io/ent"
	"entgo.io/ent/schema/edge"
	"entgo.io/ent/schema/field"
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
		field.String("description").Optional(),
		field.String("image_path").Optional(),
		field.Time("created_at").
			Default(time.Now).
			Immutable().
			Comment("Time when the user was created"),
		field.Time("updated_at").
			Default(time.Now).
			UpdateDefault(time.Now).
			Comment("Time when the user was last updated"),
	}

}

// Edges of the Author.
func (Author) Edges() []ent.Edge {
	return []ent.Edge{
		edge.To("books", Book.Type),
	}
}
