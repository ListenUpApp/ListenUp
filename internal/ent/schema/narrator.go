package schema

import (
	"entgo.io/ent"
	"entgo.io/ent/schema/edge"
	"entgo.io/ent/schema/field"
	"entgo.io/ent/schema/index"
	"github.com/ListenUpApp/ListenUp/internal/util"
)

type Narrator struct {
	ent.Schema
}

func (Narrator) Fields() []ent.Field {
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

func (Narrator) Edges() []ent.Edge {
	return []ent.Edge{
		edge.To("books", Book.Type),
	}
}

func (Narrator) Indexes() []ent.Index {
	return []ent.Index{
		index.Fields("name_sort"),
	}
}
