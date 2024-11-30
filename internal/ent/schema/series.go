package schema

import (
	"entgo.io/ent"
	"entgo.io/ent/schema/edge"
	"entgo.io/ent/schema/field"
	"github.com/ListenUpApp/ListenUp/internal/util"
	"time"
)

type Series struct {
	ent.Schema
}

func (Series) Fields() []ent.Field {
	return []ent.Field{
		field.String("id").
			DefaultFunc(util.NewID).
			Unique().
			Immutable(),
		field.String("name"),
		field.String("name_sort"),
		field.String("description").
			Optional(),
		field.Time("created_at").
			Default(time.Now).
			Immutable(),
		field.Time("updated_at").
			Default(time.Now).
			UpdateDefault(time.Now),
	}
}

func (Series) Edges() []ent.Edge {
	return []ent.Edge{
		edge.To("series_books", SeriesBook.Type),
	}
}

// SeriesBook schema (join table)
type SeriesBook struct {
	ent.Schema
}

func (SeriesBook) Fields() []ent.Field {
	return []ent.Field{
		field.Float("sequence").
			Comment("Position in series (e.g., 1.0, 1.5, 2.0)"),
	}
}

func (SeriesBook) Edges() []ent.Edge {
	return []ent.Edge{
		edge.From("series", Series.Type).
			Ref("series_books").
			Unique().
			Required(),
		edge.From("book", Book.Type).
			Ref("series_books").
			Unique().
			Required(),
	}
}
