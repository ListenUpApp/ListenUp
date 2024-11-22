package schema

import (
	"time"

	"entgo.io/ent"
	"entgo.io/ent/schema/edge"
	"entgo.io/ent/schema/field"
	"entgo.io/ent/schema/index"
)

// Server holds the schema definition for the Server entity.
type Server struct {
	ent.Schema
}

// Fields of the Server.
func (Server) Fields() []ent.Field {
	return []ent.Field{
		field.Bool("setup").
			Default(false).
			Comment("Indicates if the server has completed initial setup"),

		field.Time("created_at").
			Default(time.Now).
			Immutable().
			Comment("Time when the server was created"),

		field.Time("updated_at").
			Default(time.Now).
			UpdateDefault(time.Now).
			Comment("Time when the server was last updated"),
	}
}

// Edges of the Server.
func (Server) Edges() []ent.Edge {
	return []ent.Edge{
		edge.To("config", ServerConfig.Type).
			Unique().   // Ensures one-to-one relationship
			Required(). // Server must have a config
			Comment("The server's configuration"),
	}
}

// Indexes of the Server.
func (Server) Indexes() []ent.Index {
	return []ent.Index{
		// This index helps enforce the singleton pattern at the DB level
		// by ensuring we can't have more than one server record
		index.Fields("created_at").
			Unique(),
	}
}

// ServerConfig holds the schema definition for the ServerConfig entity.
type ServerConfig struct {
	ent.Schema
}

// Fields of the ServerConfig.
func (ServerConfig) Fields() []ent.Field {
	return []ent.Field{
		field.String("name").
			NotEmpty().
			Comment("Name of the server instance"),

		field.Time("created_at").
			Default(time.Now).
			Immutable().
			Comment("Time when the config was created"),

		field.Time("updated_at").
			Default(time.Now).
			UpdateDefault(time.Now).
			Comment("Time when the config was last updated"),
	}
}

// Edges of the ServerConfig.
func (ServerConfig) Edges() []ent.Edge {
	return []ent.Edge{
		edge.From("server", Server.Type).
			Ref("config").
			Unique(). // Ensures one-to-one relationship
			Comment("The server this config belongs to"),
	}
}

// Indexes of the ServerConfig.
func (ServerConfig) Indexes() []ent.Index {
	return []ent.Index{
		// This ensures each config is associated with exactly one server
		index.Edges("server").
			Unique(),
	}
}
