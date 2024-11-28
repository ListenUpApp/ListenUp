// Code generated by ent, DO NOT EDIT.

package migrate

import (
	"entgo.io/ent/dialect/sql/schema"
	"entgo.io/ent/schema/field"
)

var (
	// AuthorsColumns holds the columns for the "authors" table.
	AuthorsColumns = []*schema.Column{
		{Name: "id", Type: field.TypeString, Unique: true},
		{Name: "name", Type: field.TypeString},
		{Name: "description", Type: field.TypeString, Nullable: true},
		{Name: "image_path", Type: field.TypeString, Nullable: true},
		{Name: "created_at", Type: field.TypeTime},
		{Name: "updated_at", Type: field.TypeTime},
	}
	// AuthorsTable holds the schema information for the "authors" table.
	AuthorsTable = &schema.Table{
		Name:       "authors",
		Columns:    AuthorsColumns,
		PrimaryKey: []*schema.Column{AuthorsColumns[0]},
	}
	// BooksColumns holds the columns for the "books" table.
	BooksColumns = []*schema.Column{
		{Name: "id", Type: field.TypeString, Unique: true},
		{Name: "duration", Type: field.TypeFloat64},
		{Name: "size", Type: field.TypeInt64},
		{Name: "title", Type: field.TypeString},
		{Name: "subtitle", Type: field.TypeString, Nullable: true},
		{Name: "description", Type: field.TypeString, Nullable: true},
		{Name: "isbn", Type: field.TypeString, Nullable: true},
		{Name: "asin", Type: field.TypeString, Nullable: true},
		{Name: "language", Type: field.TypeString, Nullable: true},
		{Name: "explicit", Type: field.TypeBool, Default: false},
		{Name: "publisher", Type: field.TypeString, Nullable: true},
		{Name: "published_date", Type: field.TypeTime, Nullable: true},
		{Name: "genres", Type: field.TypeJSON, Nullable: true},
		{Name: "tags", Type: field.TypeJSON, Nullable: true},
		{Name: "created_at", Type: field.TypeTime},
		{Name: "updated_at", Type: field.TypeTime},
		{Name: "folder_books", Type: field.TypeString},
		{Name: "library_library_books", Type: field.TypeString},
	}
	// BooksTable holds the schema information for the "books" table.
	BooksTable = &schema.Table{
		Name:       "books",
		Columns:    BooksColumns,
		PrimaryKey: []*schema.Column{BooksColumns[0]},
		ForeignKeys: []*schema.ForeignKey{
			{
				Symbol:     "books_folders_books",
				Columns:    []*schema.Column{BooksColumns[16]},
				RefColumns: []*schema.Column{FoldersColumns[0]},
				OnDelete:   schema.NoAction,
			},
			{
				Symbol:     "books_libraries_library_books",
				Columns:    []*schema.Column{BooksColumns[17]},
				RefColumns: []*schema.Column{LibrariesColumns[0]},
				OnDelete:   schema.NoAction,
			},
		},
	}
	// BookCoversColumns holds the columns for the "book_covers" table.
	BookCoversColumns = []*schema.Column{
		{Name: "id", Type: field.TypeInt, Increment: true},
		{Name: "path", Type: field.TypeString},
		{Name: "format", Type: field.TypeString},
		{Name: "size", Type: field.TypeInt64},
		{Name: "updated_at", Type: field.TypeTime},
		{Name: "book_cover", Type: field.TypeString, Unique: true},
	}
	// BookCoversTable holds the schema information for the "book_covers" table.
	BookCoversTable = &schema.Table{
		Name:       "book_covers",
		Columns:    BookCoversColumns,
		PrimaryKey: []*schema.Column{BookCoversColumns[0]},
		ForeignKeys: []*schema.ForeignKey{
			{
				Symbol:     "book_covers_books_cover",
				Columns:    []*schema.Column{BookCoversColumns[5]},
				RefColumns: []*schema.Column{BooksColumns[0]},
				OnDelete:   schema.NoAction,
			},
		},
	}
	// ChaptersColumns holds the columns for the "chapters" table.
	ChaptersColumns = []*schema.Column{
		{Name: "id", Type: field.TypeInt, Increment: true},
		{Name: "index", Type: field.TypeInt},
		{Name: "title", Type: field.TypeString, Size: 500},
		{Name: "start", Type: field.TypeFloat64},
		{Name: "end", Type: field.TypeFloat64},
		{Name: "book_chapters", Type: field.TypeString},
	}
	// ChaptersTable holds the schema information for the "chapters" table.
	ChaptersTable = &schema.Table{
		Name:       "chapters",
		Columns:    ChaptersColumns,
		PrimaryKey: []*schema.Column{ChaptersColumns[0]},
		ForeignKeys: []*schema.ForeignKey{
			{
				Symbol:     "chapters_books_chapters",
				Columns:    []*schema.Column{ChaptersColumns[5]},
				RefColumns: []*schema.Column{BooksColumns[0]},
				OnDelete:   schema.NoAction,
			},
		},
		Indexes: []*schema.Index{
			{
				Name:    "chapter_index_book_chapters",
				Unique:  true,
				Columns: []*schema.Column{ChaptersColumns[1], ChaptersColumns[5]},
			},
		},
	}
	// FoldersColumns holds the columns for the "folders" table.
	FoldersColumns = []*schema.Column{
		{Name: "id", Type: field.TypeString, Unique: true},
		{Name: "name", Type: field.TypeString, Unique: true},
		{Name: "path", Type: field.TypeString},
		{Name: "last_scanned_at", Type: field.TypeTime, Nullable: true},
	}
	// FoldersTable holds the schema information for the "folders" table.
	FoldersTable = &schema.Table{
		Name:       "folders",
		Columns:    FoldersColumns,
		PrimaryKey: []*schema.Column{FoldersColumns[0]},
	}
	// LibrariesColumns holds the columns for the "libraries" table.
	LibrariesColumns = []*schema.Column{
		{Name: "id", Type: field.TypeString, Unique: true},
		{Name: "name", Type: field.TypeString},
	}
	// LibrariesTable holds the schema information for the "libraries" table.
	LibrariesTable = &schema.Table{
		Name:       "libraries",
		Columns:    LibrariesColumns,
		PrimaryKey: []*schema.Column{LibrariesColumns[0]},
		Indexes: []*schema.Index{
			{
				Name:    "library_name",
				Unique:  false,
				Columns: []*schema.Column{LibrariesColumns[1]},
			},
		},
	}
	// NarratorsColumns holds the columns for the "narrators" table.
	NarratorsColumns = []*schema.Column{
		{Name: "id", Type: field.TypeString, Unique: true},
		{Name: "name", Type: field.TypeString},
		{Name: "description", Type: field.TypeString, Nullable: true},
		{Name: "image_path", Type: field.TypeString, Nullable: true},
		{Name: "created_at", Type: field.TypeTime},
		{Name: "updated_at", Type: field.TypeTime},
	}
	// NarratorsTable holds the schema information for the "narrators" table.
	NarratorsTable = &schema.Table{
		Name:       "narrators",
		Columns:    NarratorsColumns,
		PrimaryKey: []*schema.Column{NarratorsColumns[0]},
	}
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
		{Name: "user_active_library", Type: field.TypeString, Nullable: true},
	}
	// UsersTable holds the schema information for the "users" table.
	UsersTable = &schema.Table{
		Name:       "users",
		Columns:    UsersColumns,
		PrimaryKey: []*schema.Column{UsersColumns[0]},
		ForeignKeys: []*schema.ForeignKey{
			{
				Symbol:     "users_libraries_active_library",
				Columns:    []*schema.Column{UsersColumns[7]},
				RefColumns: []*schema.Column{LibrariesColumns[0]},
				OnDelete:   schema.SetNull,
			},
		},
	}
	// AuthorBooksColumns holds the columns for the "author_books" table.
	AuthorBooksColumns = []*schema.Column{
		{Name: "author_id", Type: field.TypeString},
		{Name: "book_id", Type: field.TypeString},
	}
	// AuthorBooksTable holds the schema information for the "author_books" table.
	AuthorBooksTable = &schema.Table{
		Name:       "author_books",
		Columns:    AuthorBooksColumns,
		PrimaryKey: []*schema.Column{AuthorBooksColumns[0], AuthorBooksColumns[1]},
		ForeignKeys: []*schema.ForeignKey{
			{
				Symbol:     "author_books_author_id",
				Columns:    []*schema.Column{AuthorBooksColumns[0]},
				RefColumns: []*schema.Column{AuthorsColumns[0]},
				OnDelete:   schema.Cascade,
			},
			{
				Symbol:     "author_books_book_id",
				Columns:    []*schema.Column{AuthorBooksColumns[1]},
				RefColumns: []*schema.Column{BooksColumns[0]},
				OnDelete:   schema.Cascade,
			},
		},
	}
	// LibraryFoldersColumns holds the columns for the "library_folders" table.
	LibraryFoldersColumns = []*schema.Column{
		{Name: "library_id", Type: field.TypeString},
		{Name: "folder_id", Type: field.TypeString},
	}
	// LibraryFoldersTable holds the schema information for the "library_folders" table.
	LibraryFoldersTable = &schema.Table{
		Name:       "library_folders",
		Columns:    LibraryFoldersColumns,
		PrimaryKey: []*schema.Column{LibraryFoldersColumns[0], LibraryFoldersColumns[1]},
		ForeignKeys: []*schema.ForeignKey{
			{
				Symbol:     "library_folders_library_id",
				Columns:    []*schema.Column{LibraryFoldersColumns[0]},
				RefColumns: []*schema.Column{LibrariesColumns[0]},
				OnDelete:   schema.Cascade,
			},
			{
				Symbol:     "library_folders_folder_id",
				Columns:    []*schema.Column{LibraryFoldersColumns[1]},
				RefColumns: []*schema.Column{FoldersColumns[0]},
				OnDelete:   schema.Cascade,
			},
		},
	}
	// NarratorBooksColumns holds the columns for the "narrator_books" table.
	NarratorBooksColumns = []*schema.Column{
		{Name: "narrator_id", Type: field.TypeString},
		{Name: "book_id", Type: field.TypeString},
	}
	// NarratorBooksTable holds the schema information for the "narrator_books" table.
	NarratorBooksTable = &schema.Table{
		Name:       "narrator_books",
		Columns:    NarratorBooksColumns,
		PrimaryKey: []*schema.Column{NarratorBooksColumns[0], NarratorBooksColumns[1]},
		ForeignKeys: []*schema.ForeignKey{
			{
				Symbol:     "narrator_books_narrator_id",
				Columns:    []*schema.Column{NarratorBooksColumns[0]},
				RefColumns: []*schema.Column{NarratorsColumns[0]},
				OnDelete:   schema.Cascade,
			},
			{
				Symbol:     "narrator_books_book_id",
				Columns:    []*schema.Column{NarratorBooksColumns[1]},
				RefColumns: []*schema.Column{BooksColumns[0]},
				OnDelete:   schema.Cascade,
			},
		},
	}
	// UserLibrariesColumns holds the columns for the "user_libraries" table.
	UserLibrariesColumns = []*schema.Column{
		{Name: "user_id", Type: field.TypeString},
		{Name: "library_id", Type: field.TypeString},
	}
	// UserLibrariesTable holds the schema information for the "user_libraries" table.
	UserLibrariesTable = &schema.Table{
		Name:       "user_libraries",
		Columns:    UserLibrariesColumns,
		PrimaryKey: []*schema.Column{UserLibrariesColumns[0], UserLibrariesColumns[1]},
		ForeignKeys: []*schema.ForeignKey{
			{
				Symbol:     "user_libraries_user_id",
				Columns:    []*schema.Column{UserLibrariesColumns[0]},
				RefColumns: []*schema.Column{UsersColumns[0]},
				OnDelete:   schema.Cascade,
			},
			{
				Symbol:     "user_libraries_library_id",
				Columns:    []*schema.Column{UserLibrariesColumns[1]},
				RefColumns: []*schema.Column{LibrariesColumns[0]},
				OnDelete:   schema.Cascade,
			},
		},
	}
	// Tables holds all the tables in the schema.
	Tables = []*schema.Table{
		AuthorsTable,
		BooksTable,
		BookCoversTable,
		ChaptersTable,
		FoldersTable,
		LibrariesTable,
		NarratorsTable,
		ServersTable,
		ServerConfigsTable,
		UsersTable,
		AuthorBooksTable,
		LibraryFoldersTable,
		NarratorBooksTable,
		UserLibrariesTable,
	}
)

func init() {
	BooksTable.ForeignKeys[0].RefTable = FoldersTable
	BooksTable.ForeignKeys[1].RefTable = LibrariesTable
	BookCoversTable.ForeignKeys[0].RefTable = BooksTable
	ChaptersTable.ForeignKeys[0].RefTable = BooksTable
	ServerConfigsTable.ForeignKeys[0].RefTable = ServersTable
	UsersTable.ForeignKeys[0].RefTable = LibrariesTable
	AuthorBooksTable.ForeignKeys[0].RefTable = AuthorsTable
	AuthorBooksTable.ForeignKeys[1].RefTable = BooksTable
	LibraryFoldersTable.ForeignKeys[0].RefTable = LibrariesTable
	LibraryFoldersTable.ForeignKeys[1].RefTable = FoldersTable
	NarratorBooksTable.ForeignKeys[0].RefTable = NarratorsTable
	NarratorBooksTable.ForeignKeys[1].RefTable = BooksTable
	UserLibrariesTable.ForeignKeys[0].RefTable = UsersTable
	UserLibrariesTable.ForeignKeys[1].RefTable = LibrariesTable
}
