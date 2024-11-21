package schema

import (
	"entgo.io/ent"
	"entgo.io/ent/schema/field"
	"github.com/ListenUpApp/ListenUp/internal/util"
	"time"
)

// User holds the schema definition for the User entity.
type User struct {
	ent.Schema
}

// Fields of the User.
func (User) Fields() []ent.Field {
	return []ent.Field{
		field.String("id").
			DefaultFunc(util.NewID).
			Unique().
			Immutable().
			Comment("Unique identifier for the user"),
		field.String("first_name").
			NotEmpty().
			Comment("The user's first name"),
		field.String("last_name").
			NotEmpty().
			Comment("The user's last name"),
		field.String("email").
			NotEmpty().
			Unique().
			Comment("The user's email"),
		field.String("password_hash").
			Sensitive().
			NotEmpty().
			Comment("Hashed password of the user"),
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

// Edges of the User.
func (User) Edges() []ent.Edge {
	return nil
}
