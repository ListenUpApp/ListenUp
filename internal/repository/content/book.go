package content

import (
	"context"
	"time"

	"github.com/ListenUpApp/ListenUp/internal/ent"
	"github.com/ListenUpApp/ListenUp/internal/ent/author"
	"github.com/ListenUpApp/ListenUp/internal/ent/book"
	"github.com/ListenUpApp/ListenUp/internal/ent/narrator"
	appErr "github.com/ListenUpApp/ListenUp/internal/error"
	logging "github.com/ListenUpApp/ListenUp/internal/logger"
	"github.com/ListenUpApp/ListenUp/internal/models"
)

type bookRepository struct {
	client *ent.Client
	logger *logging.AppLogger
}

func (r *bookRepository) Create(ctx context.Context, bookID string, params models.CreateAudiobookRequest, folder *ent.Folder, library *ent.Library) (*ent.Book, error) {
	tx, err := r.client.Tx(ctx)
	if err != nil {
		return nil, appErr.NewRepositoryError(appErr.ErrDatabase, "failed to start transaction", err).
			WithOperation("CreateAudiobook")
	}
	defer tx.Rollback()

	// Process authors
	var authors []*ent.Author
	for _, authorReq := range params.Authors {
		dbAuthor, err := tx.Author.Query().
			Where(author.NameEQ(authorReq.Name)).
			Only(ctx)

		if err != nil {
			if !ent.IsNotFound(err) {
				return nil, appErr.NewRepositoryError(appErr.ErrDatabase, "failed to query dbAuthor", err).
					WithOperation("CreateAudiobook").
					WithData(map[string]interface{}{"author_name": authorReq.Name})
			}

			dbAuthor, err = tx.Author.Create().
				SetName(authorReq.Name).
				SetDescription(authorReq.Description).
				Save(ctx)
			if err != nil {
				return nil, appErr.NewRepositoryError(appErr.ErrDatabase, "failed to create dbAuthor", err).
					WithOperation("CreateAudiobook").
					WithData(map[string]interface{}{"author_name": authorReq.Name})
			}
		}
		authors = append(authors, dbAuthor)
	}

	// Process narrators
	var narrators []*ent.Narrator
	for _, narratorReq := range params.Narrators {
		dbNarrator, err := tx.Narrator.Query().
			Where(narrator.NameEQ(narratorReq.Name)).
			Only(ctx)

		if err != nil {
			if !ent.IsNotFound(err) {
				return nil, appErr.NewRepositoryError(appErr.ErrDatabase, "failed to query dbNarrator", err).
					WithOperation("CreateAudiobook").
					WithData(map[string]interface{}{"narrator_name": narratorReq.Name})
			}

			dbNarrator, err = tx.Narrator.Create().
				SetName(narratorReq.Name).
				SetDescription(narratorReq.Description).
				Save(ctx)
			if err != nil {
				return nil, appErr.NewRepositoryError(appErr.ErrDatabase, "failed to create dbNarrator", err).
					WithOperation("CreateAudiobook").
					WithData(map[string]interface{}{"narrator_name": narratorReq.Name})
			}
		}
		narrators = append(narrators, dbNarrator)
	}

	// Create book
	dbBook, err := tx.Book.Create().
		SetID(bookID).
		SetTitle(params.Title).
		SetDuration(float64(params.Duration)).
		SetSize(params.Size).
		SetSubtitle(params.Subtitle).
		SetDescription(params.Description).
		SetIsbn(params.Isbn).
		SetAsin(params.Asin).
		SetLanguage(params.Language).
		SetPublisher(params.Publisher).
		SetPublishedDate(params.PublishedDate).
		SetGenres(params.Genres).
		AddAuthors(authors...).
		AddNarrators(narrators...).
		SetLibrary(library).
		SetFolder(folder).
		Save(ctx)

	if err != nil {
		return nil, appErr.NewRepositoryError(appErr.ErrDatabase, "failed to create book", err).
			WithOperation("CreateAudiobook").
			WithData(map[string]interface{}{
				"title":          params.Title,
				"author_count":   len(authors),
				"narrator_count": len(narrators),
			})
	}

	// Create chapters
	if len(params.Chapter) > 0 {
		for i, chapter := range params.Chapter {
			_, err := tx.Chapter.Create().
				SetIndex(i).
				SetTitle(chapter.Title).
				SetStart(chapter.Start).
				SetEnd(chapter.End).
				SetBook(dbBook).
				Save(ctx)

			if err != nil {
				return nil, appErr.NewRepositoryError(appErr.ErrDatabase, "failed to create chapter", err).
					WithOperation("CreateAudiobook").
					WithData(map[string]interface{}{
						"book_id":       dbBook.ID,
						"chapter_index": i,
					})
			}
		}
	}

	// Create cover if provided
	// Create cover if provided
	if params.Cover.Path != "" {
		// Create main cover
		cover, err := tx.BookCover.Create().
			SetPath(params.Cover.Path).
			SetFormat(params.Cover.Format).
			SetSize(params.Cover.Size).
			SetBook(dbBook).
			SetUpdatedAt(time.Now()).
			Save(ctx)

		if err != nil {
			return nil, appErr.NewRepositoryError(appErr.ErrDatabase, "failed to create cover", err)
		}

		// Create cover versions
		for _, version := range params.Cover.Versions {
			_, err := tx.CoverVersion.Create().
				SetPath(version.Path).
				SetFormat(version.Format).
				SetSize(version.Size).
				SetSuffix(version.Suffix).
				SetCover(cover).
				SetUpdatedAt(time.Now()).
				Save(ctx)

			if err != nil {
				return nil, appErr.NewRepositoryError(appErr.ErrDatabase,
					"failed to create cover version", err)
			}
		}
	}

	// Load all relationships
	query := tx.Book.Query().
		Where(book.ID(dbBook.ID)).
		WithAuthors().
		WithNarrators().
		WithChapters().
		WithCover(func(q *ent.BookCoverQuery) {
			q.WithVersions()
		})

	dbBook, err = query.Only(ctx)
	if err != nil {
		return nil, appErr.NewRepositoryError(appErr.ErrDatabase, "failed to load relationships", err)
	}

	if err := tx.Commit(); err != nil {
		return nil, appErr.NewRepositoryError(appErr.ErrDatabase, "failed to commit transaction", err)
	}

	return dbBook, nil
}
