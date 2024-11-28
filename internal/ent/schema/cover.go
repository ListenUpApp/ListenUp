package schema

import (
	"fmt"
	"time"

	"entgo.io/ent"
	"entgo.io/ent/schema/edge"
	"entgo.io/ent/schema/field"
)

type BookCover struct {
	ent.Schema
}

// Fields of the BookCover.
func (BookCover) Fields() []ent.Field {
	return []ent.Field{
		field.String("path").
			NotEmpty().
			Comment("Path to the cover image"),

		field.String("format").
			NotEmpty().
			Validate(func(s string) error {
				switch s {
				case "image/jpeg", "image/png", "image/webp":
					return nil
				default:
					return fmt.Errorf("invalid image format: %s. Must be one of: jpeg, png, webp", s)
				}
			}).
			Comment("Image format (jpeg, png, webp)"),
		field.Int64("size").
			Positive().
			Comment("File size in bytes"),

		field.Time("updated_at").
			Default(time.Now).
			UpdateDefault(time.Now).
			Comment("Time when the user was last updated"),
	}
}

// Edges of the BookCover.
func (BookCover) Edges() []ent.Edge {
	return []ent.Edge{
		edge.From("book", Book.Type).
			Ref("cover").
			Unique().
			Required(),
	}
}
