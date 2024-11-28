package schema

import (
	"errors"

	"entgo.io/ent"
	"entgo.io/ent/schema/edge"
	"entgo.io/ent/schema/field"
	"entgo.io/ent/schema/index"
)

// Chapter holds the schema definition for audiobook chapters
type Chapter struct {
	ent.Schema
}

func (Chapter) Fields() []ent.Field {
	return []ent.Field{
		field.Int("index").
			NonNegative().
			Comment("Chapter's position in the book"),

		field.String("title").
			NotEmpty().
			MaxLen(500).
			Comment("Chapter title").
			Validate(func(s string) error {
				if len(s) < 1 {
					return errors.New("chapter title cannot be empty")
				}
				return nil
			}),

		field.Float("start").
			Min(0).
			Comment("Start time in seconds"),

		field.Float("end").
			Min(0).
			Comment("End time in seconds").
			Validate(func(end float64) error {
				if end <= 0 {
					return errors.New("end time must be greater than 0")
				}
				return nil
			}),
	}
}

func (Chapter) Edges() []ent.Edge {
	return []ent.Edge{
		edge.From("book", Book.Type).
			Ref("chapters").
			Unique().
			Required(),
	}
}

// Indexes ensures that each chapter index is unique within a book
func (Chapter) Indexes() []ent.Index {
	return []ent.Index{
		index.Fields("index").
			Edges("book").
			Unique(),
	}
}
