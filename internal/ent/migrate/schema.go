// Code generated by ent, DO NOT EDIT.

package migrate

import (
	"entgo.io/ent/dialect/sql/schema"
	"entgo.io/ent/schema/field"
)

var (
	// ServersColumns holds the columns for the "servers" table.
	ServersColumns = []*schema.Column{
		{Name: "id", Type: field.TypeInt, Increment: true},
		{Name: "setup", Type: field.TypeBool, Default: false},
		{Name: "created_at", Type: field.TypeTime},
		{Name: "updated_at", Type: field.TypeTime},
	}
	// ServersTable holds the schema information for the "servers" table.
	ServersTable = &schema.Table{
		Name:       "servers",
		Columns:    ServersColumns,
		PrimaryKey: []*schema.Column{ServersColumns[0]},
		Indexes: []*schema.Index{
			{
				Name:    "server_created_at",
				Unique:  true,
				Columns: []*schema.Column{ServersColumns[2]},
			},
		},
	}
	// ServerConfigsColumns holds the columns for the "server_configs" table.
	ServerConfigsColumns = []*schema.Column{
		{Name: "id", Type: field.TypeInt, Increment: true},
		{Name: "name", Type: field.TypeString},
		{Name: "created_at", Type: field.TypeTime},
		{Name: "updated_at", Type: field.TypeTime},
		{Name: "server_config", Type: field.TypeInt, Unique: true, Nullable: true},
	}
	// ServerConfigsTable holds the schema information for the "server_configs" table.
	ServerConfigsTable = &schema.Table{
		Name:       "server_configs",
		Columns:    ServerConfigsColumns,
		PrimaryKey: []*schema.Column{ServerConfigsColumns[0]},
		ForeignKeys: []*schema.ForeignKey{
			{
				Symbol:     "server_configs_servers_config",
				Columns:    []*schema.Column{ServerConfigsColumns[4]},
				RefColumns: []*schema.Column{ServersColumns[0]},
				OnDelete:   schema.SetNull,
			},
		},
		Indexes: []*schema.Index{
			{
				Name:    "serverconfig_server_config",
				Unique:  true,
				Columns: []*schema.Column{ServerConfigsColumns[4]},
			},
		},
	}
	// UsersColumns holds the columns for the "users" table.
	UsersColumns = []*schema.Column{
		{Name: "id", Type: field.TypeString, Unique: true},
		{Name: "first_name", Type: field.TypeString},
		{Name: "last_name", Type: field.TypeString},
		{Name: "email", Type: field.TypeString, Unique: true},
		{Name: "password_hash", Type: field.TypeString},
		{Name: "created_at", Type: field.TypeTime},
		{Name: "updated_at", Type: field.TypeTime},
	}
	// UsersTable holds the schema information for the "users" table.
	UsersTable = &schema.Table{
		Name:       "users",
		Columns:    UsersColumns,
		PrimaryKey: []*schema.Column{UsersColumns[0]},
	}
	// Tables holds all the tables in the schema.
	Tables = []*schema.Table{
		ServersTable,
		ServerConfigsTable,
		UsersTable,
	}
)

func init() {
	ServerConfigsTable.ForeignKeys[0].RefTable = ServersTable
}
