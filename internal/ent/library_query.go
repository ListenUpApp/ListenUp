// Code generated by ent, DO NOT EDIT.

package ent

import (
	"context"
	"database/sql/driver"
	"fmt"
	"math"

	"entgo.io/ent"
	"entgo.io/ent/dialect/sql"
	"entgo.io/ent/dialect/sql/sqlgraph"
	"entgo.io/ent/schema/field"
	"github.com/ListenUpApp/ListenUp/internal/ent/book"
	"github.com/ListenUpApp/ListenUp/internal/ent/folder"
	"github.com/ListenUpApp/ListenUp/internal/ent/library"
	"github.com/ListenUpApp/ListenUp/internal/ent/predicate"
	"github.com/ListenUpApp/ListenUp/internal/ent/user"
)

// LibraryQuery is the builder for querying Library entities.
type LibraryQuery struct {
	config
	ctx              *QueryContext
	order            []library.OrderOption
	inters           []Interceptor
	predicates       []predicate.Library
	withUsers        *UserQuery
	withActiveUsers  *UserQuery
	withFolders      *FolderQuery
	withLibraryBooks *BookQuery
	// intermediate query (i.e. traversal path).
	sql  *sql.Selector
	path func(context.Context) (*sql.Selector, error)
}

// Where adds a new predicate for the LibraryQuery builder.
func (lq *LibraryQuery) Where(ps ...predicate.Library) *LibraryQuery {
	lq.predicates = append(lq.predicates, ps...)
	return lq
}

// Limit the number of records to be returned by this query.
func (lq *LibraryQuery) Limit(limit int) *LibraryQuery {
	lq.ctx.Limit = &limit
	return lq
}

// Offset to start from.
func (lq *LibraryQuery) Offset(offset int) *LibraryQuery {
	lq.ctx.Offset = &offset
	return lq
}

// Unique configures the query builder to filter duplicate records on query.
// By default, unique is set to true, and can be disabled using this method.
func (lq *LibraryQuery) Unique(unique bool) *LibraryQuery {
	lq.ctx.Unique = &unique
	return lq
}

// Order specifies how the records should be ordered.
func (lq *LibraryQuery) Order(o ...library.OrderOption) *LibraryQuery {
	lq.order = append(lq.order, o...)
	return lq
}

// QueryUsers chains the current query on the "users" edge.
func (lq *LibraryQuery) QueryUsers() *UserQuery {
	query := (&UserClient{config: lq.config}).Query()
	query.path = func(ctx context.Context) (fromU *sql.Selector, err error) {
		if err := lq.prepareQuery(ctx); err != nil {
			return nil, err
		}
		selector := lq.sqlQuery(ctx)
		if err := selector.Err(); err != nil {
			return nil, err
		}
		step := sqlgraph.NewStep(
			sqlgraph.From(library.Table, library.FieldID, selector),
			sqlgraph.To(user.Table, user.FieldID),
			sqlgraph.Edge(sqlgraph.M2M, true, library.UsersTable, library.UsersPrimaryKey...),
		)
		fromU = sqlgraph.SetNeighbors(lq.driver.Dialect(), step)
		return fromU, nil
	}
	return query
}

// QueryActiveUsers chains the current query on the "active_users" edge.
func (lq *LibraryQuery) QueryActiveUsers() *UserQuery {
	query := (&UserClient{config: lq.config}).Query()
	query.path = func(ctx context.Context) (fromU *sql.Selector, err error) {
		if err := lq.prepareQuery(ctx); err != nil {
			return nil, err
		}
		selector := lq.sqlQuery(ctx)
		if err := selector.Err(); err != nil {
			return nil, err
		}
		step := sqlgraph.NewStep(
			sqlgraph.From(library.Table, library.FieldID, selector),
			sqlgraph.To(user.Table, user.FieldID),
			sqlgraph.Edge(sqlgraph.O2M, true, library.ActiveUsersTable, library.ActiveUsersColumn),
		)
		fromU = sqlgraph.SetNeighbors(lq.driver.Dialect(), step)
		return fromU, nil
	}
	return query
}

// QueryFolders chains the current query on the "folders" edge.
func (lq *LibraryQuery) QueryFolders() *FolderQuery {
	query := (&FolderClient{config: lq.config}).Query()
	query.path = func(ctx context.Context) (fromU *sql.Selector, err error) {
		if err := lq.prepareQuery(ctx); err != nil {
			return nil, err
		}
		selector := lq.sqlQuery(ctx)
		if err := selector.Err(); err != nil {
			return nil, err
		}
		step := sqlgraph.NewStep(
			sqlgraph.From(library.Table, library.FieldID, selector),
			sqlgraph.To(folder.Table, folder.FieldID),
			sqlgraph.Edge(sqlgraph.M2M, false, library.FoldersTable, library.FoldersPrimaryKey...),
		)
		fromU = sqlgraph.SetNeighbors(lq.driver.Dialect(), step)
		return fromU, nil
	}
	return query
}

// QueryLibraryBooks chains the current query on the "library_books" edge.
func (lq *LibraryQuery) QueryLibraryBooks() *BookQuery {
	query := (&BookClient{config: lq.config}).Query()
	query.path = func(ctx context.Context) (fromU *sql.Selector, err error) {
		if err := lq.prepareQuery(ctx); err != nil {
			return nil, err
		}
		selector := lq.sqlQuery(ctx)
		if err := selector.Err(); err != nil {
			return nil, err
		}
		step := sqlgraph.NewStep(
			sqlgraph.From(library.Table, library.FieldID, selector),
			sqlgraph.To(book.Table, book.FieldID),
			sqlgraph.Edge(sqlgraph.O2M, false, library.LibraryBooksTable, library.LibraryBooksColumn),
		)
		fromU = sqlgraph.SetNeighbors(lq.driver.Dialect(), step)
		return fromU, nil
	}
	return query
}

// First returns the first Library entity from the query.
// Returns a *NotFoundError when no Library was found.
func (lq *LibraryQuery) First(ctx context.Context) (*Library, error) {
	nodes, err := lq.Limit(1).All(setContextOp(ctx, lq.ctx, ent.OpQueryFirst))
	if err != nil {
		return nil, err
	}
	if len(nodes) == 0 {
		return nil, &NotFoundError{library.Label}
	}
	return nodes[0], nil
}

// FirstX is like First, but panics if an error occurs.
func (lq *LibraryQuery) FirstX(ctx context.Context) *Library {
	node, err := lq.First(ctx)
	if err != nil && !IsNotFound(err) {
		panic(err)
	}
	return node
}

// FirstID returns the first Library ID from the query.
// Returns a *NotFoundError when no Library ID was found.
func (lq *LibraryQuery) FirstID(ctx context.Context) (id string, err error) {
	var ids []string
	if ids, err = lq.Limit(1).IDs(setContextOp(ctx, lq.ctx, ent.OpQueryFirstID)); err != nil {
		return
	}
	if len(ids) == 0 {
		err = &NotFoundError{library.Label}
		return
	}
	return ids[0], nil
}

// FirstIDX is like FirstID, but panics if an error occurs.
func (lq *LibraryQuery) FirstIDX(ctx context.Context) string {
	id, err := lq.FirstID(ctx)
	if err != nil && !IsNotFound(err) {
		panic(err)
	}
	return id
}

// Only returns a single Library entity found by the query, ensuring it only returns one.
// Returns a *NotSingularError when more than one Library entity is found.
// Returns a *NotFoundError when no Library entities are found.
func (lq *LibraryQuery) Only(ctx context.Context) (*Library, error) {
	nodes, err := lq.Limit(2).All(setContextOp(ctx, lq.ctx, ent.OpQueryOnly))
	if err != nil {
		return nil, err
	}
	switch len(nodes) {
	case 1:
		return nodes[0], nil
	case 0:
		return nil, &NotFoundError{library.Label}
	default:
		return nil, &NotSingularError{library.Label}
	}
}

// OnlyX is like Only, but panics if an error occurs.
func (lq *LibraryQuery) OnlyX(ctx context.Context) *Library {
	node, err := lq.Only(ctx)
	if err != nil {
		panic(err)
	}
	return node
}

// OnlyID is like Only, but returns the only Library ID in the query.
// Returns a *NotSingularError when more than one Library ID is found.
// Returns a *NotFoundError when no entities are found.
func (lq *LibraryQuery) OnlyID(ctx context.Context) (id string, err error) {
	var ids []string
	if ids, err = lq.Limit(2).IDs(setContextOp(ctx, lq.ctx, ent.OpQueryOnlyID)); err != nil {
		return
	}
	switch len(ids) {
	case 1:
		id = ids[0]
	case 0:
		err = &NotFoundError{library.Label}
	default:
		err = &NotSingularError{library.Label}
	}
	return
}

// OnlyIDX is like OnlyID, but panics if an error occurs.
func (lq *LibraryQuery) OnlyIDX(ctx context.Context) string {
	id, err := lq.OnlyID(ctx)
	if err != nil {
		panic(err)
	}
	return id
}

// All executes the query and returns a list of Libraries.
func (lq *LibraryQuery) All(ctx context.Context) ([]*Library, error) {
	ctx = setContextOp(ctx, lq.ctx, ent.OpQueryAll)
	if err := lq.prepareQuery(ctx); err != nil {
		return nil, err
	}
	qr := querierAll[[]*Library, *LibraryQuery]()
	return withInterceptors[[]*Library](ctx, lq, qr, lq.inters)
}

// AllX is like All, but panics if an error occurs.
func (lq *LibraryQuery) AllX(ctx context.Context) []*Library {
	nodes, err := lq.All(ctx)
	if err != nil {
		panic(err)
	}
	return nodes
}

// IDs executes the query and returns a list of Library IDs.
func (lq *LibraryQuery) IDs(ctx context.Context) (ids []string, err error) {
	if lq.ctx.Unique == nil && lq.path != nil {
		lq.Unique(true)
	}
	ctx = setContextOp(ctx, lq.ctx, ent.OpQueryIDs)
	if err = lq.Select(library.FieldID).Scan(ctx, &ids); err != nil {
		return nil, err
	}
	return ids, nil
}

// IDsX is like IDs, but panics if an error occurs.
func (lq *LibraryQuery) IDsX(ctx context.Context) []string {
	ids, err := lq.IDs(ctx)
	if err != nil {
		panic(err)
	}
	return ids
}

// Count returns the count of the given query.
func (lq *LibraryQuery) Count(ctx context.Context) (int, error) {
	ctx = setContextOp(ctx, lq.ctx, ent.OpQueryCount)
	if err := lq.prepareQuery(ctx); err != nil {
		return 0, err
	}
	return withInterceptors[int](ctx, lq, querierCount[*LibraryQuery](), lq.inters)
}

// CountX is like Count, but panics if an error occurs.
func (lq *LibraryQuery) CountX(ctx context.Context) int {
	count, err := lq.Count(ctx)
	if err != nil {
		panic(err)
	}
	return count
}

// Exist returns true if the query has elements in the graph.
func (lq *LibraryQuery) Exist(ctx context.Context) (bool, error) {
	ctx = setContextOp(ctx, lq.ctx, ent.OpQueryExist)
	switch _, err := lq.FirstID(ctx); {
	case IsNotFound(err):
		return false, nil
	case err != nil:
		return false, fmt.Errorf("ent: check existence: %w", err)
	default:
		return true, nil
	}
}

// ExistX is like Exist, but panics if an error occurs.
func (lq *LibraryQuery) ExistX(ctx context.Context) bool {
	exist, err := lq.Exist(ctx)
	if err != nil {
		panic(err)
	}
	return exist
}

// Clone returns a duplicate of the LibraryQuery builder, including all associated steps. It can be
// used to prepare common query builders and use them differently after the clone is made.
func (lq *LibraryQuery) Clone() *LibraryQuery {
	if lq == nil {
		return nil
	}
	return &LibraryQuery{
		config:           lq.config,
		ctx:              lq.ctx.Clone(),
		order:            append([]library.OrderOption{}, lq.order...),
		inters:           append([]Interceptor{}, lq.inters...),
		predicates:       append([]predicate.Library{}, lq.predicates...),
		withUsers:        lq.withUsers.Clone(),
		withActiveUsers:  lq.withActiveUsers.Clone(),
		withFolders:      lq.withFolders.Clone(),
		withLibraryBooks: lq.withLibraryBooks.Clone(),
		// clone intermediate query.
		sql:  lq.sql.Clone(),
		path: lq.path,
	}
}

// WithUsers tells the query-builder to eager-load the nodes that are connected to
// the "users" edge. The optional arguments are used to configure the query builder of the edge.
func (lq *LibraryQuery) WithUsers(opts ...func(*UserQuery)) *LibraryQuery {
	query := (&UserClient{config: lq.config}).Query()
	for _, opt := range opts {
		opt(query)
	}
	lq.withUsers = query
	return lq
}

// WithActiveUsers tells the query-builder to eager-load the nodes that are connected to
// the "active_users" edge. The optional arguments are used to configure the query builder of the edge.
func (lq *LibraryQuery) WithActiveUsers(opts ...func(*UserQuery)) *LibraryQuery {
	query := (&UserClient{config: lq.config}).Query()
	for _, opt := range opts {
		opt(query)
	}
	lq.withActiveUsers = query
	return lq
}

// WithFolders tells the query-builder to eager-load the nodes that are connected to
// the "folders" edge. The optional arguments are used to configure the query builder of the edge.
func (lq *LibraryQuery) WithFolders(opts ...func(*FolderQuery)) *LibraryQuery {
	query := (&FolderClient{config: lq.config}).Query()
	for _, opt := range opts {
		opt(query)
	}
	lq.withFolders = query
	return lq
}

// WithLibraryBooks tells the query-builder to eager-load the nodes that are connected to
// the "library_books" edge. The optional arguments are used to configure the query builder of the edge.
func (lq *LibraryQuery) WithLibraryBooks(opts ...func(*BookQuery)) *LibraryQuery {
	query := (&BookClient{config: lq.config}).Query()
	for _, opt := range opts {
		opt(query)
	}
	lq.withLibraryBooks = query
	return lq
}

// GroupBy is used to group vertices by one or more fields/columns.
// It is often used with aggregate functions, like: count, max, mean, min, sum.
//
// Example:
//
//	var v []struct {
//		Name string `json:"name,omitempty"`
//		Count int `json:"count,omitempty"`
//	}
//
//	client.Library.Query().
//		GroupBy(library.FieldName).
//		Aggregate(ent.Count()).
//		Scan(ctx, &v)
func (lq *LibraryQuery) GroupBy(field string, fields ...string) *LibraryGroupBy {
	lq.ctx.Fields = append([]string{field}, fields...)
	grbuild := &LibraryGroupBy{build: lq}
	grbuild.flds = &lq.ctx.Fields
	grbuild.label = library.Label
	grbuild.scan = grbuild.Scan
	return grbuild
}

// Select allows the selection one or more fields/columns for the given query,
// instead of selecting all fields in the entity.
//
// Example:
//
//	var v []struct {
//		Name string `json:"name,omitempty"`
//	}
//
//	client.Library.Query().
//		Select(library.FieldName).
//		Scan(ctx, &v)
func (lq *LibraryQuery) Select(fields ...string) *LibrarySelect {
	lq.ctx.Fields = append(lq.ctx.Fields, fields...)
	sbuild := &LibrarySelect{LibraryQuery: lq}
	sbuild.label = library.Label
	sbuild.flds, sbuild.scan = &lq.ctx.Fields, sbuild.Scan
	return sbuild
}

// Aggregate returns a LibrarySelect configured with the given aggregations.
func (lq *LibraryQuery) Aggregate(fns ...AggregateFunc) *LibrarySelect {
	return lq.Select().Aggregate(fns...)
}

func (lq *LibraryQuery) prepareQuery(ctx context.Context) error {
	for _, inter := range lq.inters {
		if inter == nil {
			return fmt.Errorf("ent: uninitialized interceptor (forgotten import ent/runtime?)")
		}
		if trv, ok := inter.(Traverser); ok {
			if err := trv.Traverse(ctx, lq); err != nil {
				return err
			}
		}
	}
	for _, f := range lq.ctx.Fields {
		if !library.ValidColumn(f) {
			return &ValidationError{Name: f, err: fmt.Errorf("ent: invalid field %q for query", f)}
		}
	}
	if lq.path != nil {
		prev, err := lq.path(ctx)
		if err != nil {
			return err
		}
		lq.sql = prev
	}
	return nil
}

func (lq *LibraryQuery) sqlAll(ctx context.Context, hooks ...queryHook) ([]*Library, error) {
	var (
		nodes       = []*Library{}
		_spec       = lq.querySpec()
		loadedTypes = [4]bool{
			lq.withUsers != nil,
			lq.withActiveUsers != nil,
			lq.withFolders != nil,
			lq.withLibraryBooks != nil,
		}
	)
	_spec.ScanValues = func(columns []string) ([]any, error) {
		return (*Library).scanValues(nil, columns)
	}
	_spec.Assign = func(columns []string, values []any) error {
		node := &Library{config: lq.config}
		nodes = append(nodes, node)
		node.Edges.loadedTypes = loadedTypes
		return node.assignValues(columns, values)
	}
	for i := range hooks {
		hooks[i](ctx, _spec)
	}
	if err := sqlgraph.QueryNodes(ctx, lq.driver, _spec); err != nil {
		return nil, err
	}
	if len(nodes) == 0 {
		return nodes, nil
	}
	if query := lq.withUsers; query != nil {
		if err := lq.loadUsers(ctx, query, nodes,
			func(n *Library) { n.Edges.Users = []*User{} },
			func(n *Library, e *User) { n.Edges.Users = append(n.Edges.Users, e) }); err != nil {
			return nil, err
		}
	}
	if query := lq.withActiveUsers; query != nil {
		if err := lq.loadActiveUsers(ctx, query, nodes,
			func(n *Library) { n.Edges.ActiveUsers = []*User{} },
			func(n *Library, e *User) { n.Edges.ActiveUsers = append(n.Edges.ActiveUsers, e) }); err != nil {
			return nil, err
		}
	}
	if query := lq.withFolders; query != nil {
		if err := lq.loadFolders(ctx, query, nodes,
			func(n *Library) { n.Edges.Folders = []*Folder{} },
			func(n *Library, e *Folder) { n.Edges.Folders = append(n.Edges.Folders, e) }); err != nil {
			return nil, err
		}
	}
	if query := lq.withLibraryBooks; query != nil {
		if err := lq.loadLibraryBooks(ctx, query, nodes,
			func(n *Library) { n.Edges.LibraryBooks = []*Book{} },
			func(n *Library, e *Book) { n.Edges.LibraryBooks = append(n.Edges.LibraryBooks, e) }); err != nil {
			return nil, err
		}
	}
	return nodes, nil
}

func (lq *LibraryQuery) loadUsers(ctx context.Context, query *UserQuery, nodes []*Library, init func(*Library), assign func(*Library, *User)) error {
	edgeIDs := make([]driver.Value, len(nodes))
	byID := make(map[string]*Library)
	nids := make(map[string]map[*Library]struct{})
	for i, node := range nodes {
		edgeIDs[i] = node.ID
		byID[node.ID] = node
		if init != nil {
			init(node)
		}
	}
	query.Where(func(s *sql.Selector) {
		joinT := sql.Table(library.UsersTable)
		s.Join(joinT).On(s.C(user.FieldID), joinT.C(library.UsersPrimaryKey[0]))
		s.Where(sql.InValues(joinT.C(library.UsersPrimaryKey[1]), edgeIDs...))
		columns := s.SelectedColumns()
		s.Select(joinT.C(library.UsersPrimaryKey[1]))
		s.AppendSelect(columns...)
		s.SetDistinct(false)
	})
	if err := query.prepareQuery(ctx); err != nil {
		return err
	}
	qr := QuerierFunc(func(ctx context.Context, q Query) (Value, error) {
		return query.sqlAll(ctx, func(_ context.Context, spec *sqlgraph.QuerySpec) {
			assign := spec.Assign
			values := spec.ScanValues
			spec.ScanValues = func(columns []string) ([]any, error) {
				values, err := values(columns[1:])
				if err != nil {
					return nil, err
				}
				return append([]any{new(sql.NullString)}, values...), nil
			}
			spec.Assign = func(columns []string, values []any) error {
				outValue := values[0].(*sql.NullString).String
				inValue := values[1].(*sql.NullString).String
				if nids[inValue] == nil {
					nids[inValue] = map[*Library]struct{}{byID[outValue]: {}}
					return assign(columns[1:], values[1:])
				}
				nids[inValue][byID[outValue]] = struct{}{}
				return nil
			}
		})
	})
	neighbors, err := withInterceptors[[]*User](ctx, query, qr, query.inters)
	if err != nil {
		return err
	}
	for _, n := range neighbors {
		nodes, ok := nids[n.ID]
		if !ok {
			return fmt.Errorf(`unexpected "users" node returned %v`, n.ID)
		}
		for kn := range nodes {
			assign(kn, n)
		}
	}
	return nil
}
func (lq *LibraryQuery) loadActiveUsers(ctx context.Context, query *UserQuery, nodes []*Library, init func(*Library), assign func(*Library, *User)) error {
	fks := make([]driver.Value, 0, len(nodes))
	nodeids := make(map[string]*Library)
	for i := range nodes {
		fks = append(fks, nodes[i].ID)
		nodeids[nodes[i].ID] = nodes[i]
		if init != nil {
			init(nodes[i])
		}
	}
	query.withFKs = true
	query.Where(predicate.User(func(s *sql.Selector) {
		s.Where(sql.InValues(s.C(library.ActiveUsersColumn), fks...))
	}))
	neighbors, err := query.All(ctx)
	if err != nil {
		return err
	}
	for _, n := range neighbors {
		fk := n.user_active_library
		if fk == nil {
			return fmt.Errorf(`foreign-key "user_active_library" is nil for node %v`, n.ID)
		}
		node, ok := nodeids[*fk]
		if !ok {
			return fmt.Errorf(`unexpected referenced foreign-key "user_active_library" returned %v for node %v`, *fk, n.ID)
		}
		assign(node, n)
	}
	return nil
}
func (lq *LibraryQuery) loadFolders(ctx context.Context, query *FolderQuery, nodes []*Library, init func(*Library), assign func(*Library, *Folder)) error {
	edgeIDs := make([]driver.Value, len(nodes))
	byID := make(map[string]*Library)
	nids := make(map[string]map[*Library]struct{})
	for i, node := range nodes {
		edgeIDs[i] = node.ID
		byID[node.ID] = node
		if init != nil {
			init(node)
		}
	}
	query.Where(func(s *sql.Selector) {
		joinT := sql.Table(library.FoldersTable)
		s.Join(joinT).On(s.C(folder.FieldID), joinT.C(library.FoldersPrimaryKey[1]))
		s.Where(sql.InValues(joinT.C(library.FoldersPrimaryKey[0]), edgeIDs...))
		columns := s.SelectedColumns()
		s.Select(joinT.C(library.FoldersPrimaryKey[0]))
		s.AppendSelect(columns...)
		s.SetDistinct(false)
	})
	if err := query.prepareQuery(ctx); err != nil {
		return err
	}
	qr := QuerierFunc(func(ctx context.Context, q Query) (Value, error) {
		return query.sqlAll(ctx, func(_ context.Context, spec *sqlgraph.QuerySpec) {
			assign := spec.Assign
			values := spec.ScanValues
			spec.ScanValues = func(columns []string) ([]any, error) {
				values, err := values(columns[1:])
				if err != nil {
					return nil, err
				}
				return append([]any{new(sql.NullString)}, values...), nil
			}
			spec.Assign = func(columns []string, values []any) error {
				outValue := values[0].(*sql.NullString).String
				inValue := values[1].(*sql.NullString).String
				if nids[inValue] == nil {
					nids[inValue] = map[*Library]struct{}{byID[outValue]: {}}
					return assign(columns[1:], values[1:])
				}
				nids[inValue][byID[outValue]] = struct{}{}
				return nil
			}
		})
	})
	neighbors, err := withInterceptors[[]*Folder](ctx, query, qr, query.inters)
	if err != nil {
		return err
	}
	for _, n := range neighbors {
		nodes, ok := nids[n.ID]
		if !ok {
			return fmt.Errorf(`unexpected "folders" node returned %v`, n.ID)
		}
		for kn := range nodes {
			assign(kn, n)
		}
	}
	return nil
}
func (lq *LibraryQuery) loadLibraryBooks(ctx context.Context, query *BookQuery, nodes []*Library, init func(*Library), assign func(*Library, *Book)) error {
	fks := make([]driver.Value, 0, len(nodes))
	nodeids := make(map[string]*Library)
	for i := range nodes {
		fks = append(fks, nodes[i].ID)
		nodeids[nodes[i].ID] = nodes[i]
		if init != nil {
			init(nodes[i])
		}
	}
	query.withFKs = true
	query.Where(predicate.Book(func(s *sql.Selector) {
		s.Where(sql.InValues(s.C(library.LibraryBooksColumn), fks...))
	}))
	neighbors, err := query.All(ctx)
	if err != nil {
		return err
	}
	for _, n := range neighbors {
		fk := n.library_library_books
		if fk == nil {
			return fmt.Errorf(`foreign-key "library_library_books" is nil for node %v`, n.ID)
		}
		node, ok := nodeids[*fk]
		if !ok {
			return fmt.Errorf(`unexpected referenced foreign-key "library_library_books" returned %v for node %v`, *fk, n.ID)
		}
		assign(node, n)
	}
	return nil
}

func (lq *LibraryQuery) sqlCount(ctx context.Context) (int, error) {
	_spec := lq.querySpec()
	_spec.Node.Columns = lq.ctx.Fields
	if len(lq.ctx.Fields) > 0 {
		_spec.Unique = lq.ctx.Unique != nil && *lq.ctx.Unique
	}
	return sqlgraph.CountNodes(ctx, lq.driver, _spec)
}

func (lq *LibraryQuery) querySpec() *sqlgraph.QuerySpec {
	_spec := sqlgraph.NewQuerySpec(library.Table, library.Columns, sqlgraph.NewFieldSpec(library.FieldID, field.TypeString))
	_spec.From = lq.sql
	if unique := lq.ctx.Unique; unique != nil {
		_spec.Unique = *unique
	} else if lq.path != nil {
		_spec.Unique = true
	}
	if fields := lq.ctx.Fields; len(fields) > 0 {
		_spec.Node.Columns = make([]string, 0, len(fields))
		_spec.Node.Columns = append(_spec.Node.Columns, library.FieldID)
		for i := range fields {
			if fields[i] != library.FieldID {
				_spec.Node.Columns = append(_spec.Node.Columns, fields[i])
			}
		}
	}
	if ps := lq.predicates; len(ps) > 0 {
		_spec.Predicate = func(selector *sql.Selector) {
			for i := range ps {
				ps[i](selector)
			}
		}
	}
	if limit := lq.ctx.Limit; limit != nil {
		_spec.Limit = *limit
	}
	if offset := lq.ctx.Offset; offset != nil {
		_spec.Offset = *offset
	}
	if ps := lq.order; len(ps) > 0 {
		_spec.Order = func(selector *sql.Selector) {
			for i := range ps {
				ps[i](selector)
			}
		}
	}
	return _spec
}

func (lq *LibraryQuery) sqlQuery(ctx context.Context) *sql.Selector {
	builder := sql.Dialect(lq.driver.Dialect())
	t1 := builder.Table(library.Table)
	columns := lq.ctx.Fields
	if len(columns) == 0 {
		columns = library.Columns
	}
	selector := builder.Select(t1.Columns(columns...)...).From(t1)
	if lq.sql != nil {
		selector = lq.sql
		selector.Select(selector.Columns(columns...)...)
	}
	if lq.ctx.Unique != nil && *lq.ctx.Unique {
		selector.Distinct()
	}
	for _, p := range lq.predicates {
		p(selector)
	}
	for _, p := range lq.order {
		p(selector)
	}
	if offset := lq.ctx.Offset; offset != nil {
		// limit is mandatory for offset clause. We start
		// with default value, and override it below if needed.
		selector.Offset(*offset).Limit(math.MaxInt32)
	}
	if limit := lq.ctx.Limit; limit != nil {
		selector.Limit(*limit)
	}
	return selector
}

// LibraryGroupBy is the group-by builder for Library entities.
type LibraryGroupBy struct {
	selector
	build *LibraryQuery
}

// Aggregate adds the given aggregation functions to the group-by query.
func (lgb *LibraryGroupBy) Aggregate(fns ...AggregateFunc) *LibraryGroupBy {
	lgb.fns = append(lgb.fns, fns...)
	return lgb
}

// Scan applies the selector query and scans the result into the given value.
func (lgb *LibraryGroupBy) Scan(ctx context.Context, v any) error {
	ctx = setContextOp(ctx, lgb.build.ctx, ent.OpQueryGroupBy)
	if err := lgb.build.prepareQuery(ctx); err != nil {
		return err
	}
	return scanWithInterceptors[*LibraryQuery, *LibraryGroupBy](ctx, lgb.build, lgb, lgb.build.inters, v)
}

func (lgb *LibraryGroupBy) sqlScan(ctx context.Context, root *LibraryQuery, v any) error {
	selector := root.sqlQuery(ctx).Select()
	aggregation := make([]string, 0, len(lgb.fns))
	for _, fn := range lgb.fns {
		aggregation = append(aggregation, fn(selector))
	}
	if len(selector.SelectedColumns()) == 0 {
		columns := make([]string, 0, len(*lgb.flds)+len(lgb.fns))
		for _, f := range *lgb.flds {
			columns = append(columns, selector.C(f))
		}
		columns = append(columns, aggregation...)
		selector.Select(columns...)
	}
	selector.GroupBy(selector.Columns(*lgb.flds...)...)
	if err := selector.Err(); err != nil {
		return err
	}
	rows := &sql.Rows{}
	query, args := selector.Query()
	if err := lgb.build.driver.Query(ctx, query, args, rows); err != nil {
		return err
	}
	defer rows.Close()
	return sql.ScanSlice(rows, v)
}

// LibrarySelect is the builder for selecting fields of Library entities.
type LibrarySelect struct {
	*LibraryQuery
	selector
}

// Aggregate adds the given aggregation functions to the selector query.
func (ls *LibrarySelect) Aggregate(fns ...AggregateFunc) *LibrarySelect {
	ls.fns = append(ls.fns, fns...)
	return ls
}

// Scan applies the selector query and scans the result into the given value.
func (ls *LibrarySelect) Scan(ctx context.Context, v any) error {
	ctx = setContextOp(ctx, ls.ctx, ent.OpQuerySelect)
	if err := ls.prepareQuery(ctx); err != nil {
		return err
	}
	return scanWithInterceptors[*LibraryQuery, *LibrarySelect](ctx, ls.LibraryQuery, ls, ls.inters, v)
}

func (ls *LibrarySelect) sqlScan(ctx context.Context, root *LibraryQuery, v any) error {
	selector := root.sqlQuery(ctx)
	aggregation := make([]string, 0, len(ls.fns))
	for _, fn := range ls.fns {
		aggregation = append(aggregation, fn(selector))
	}
	switch n := len(*ls.selector.flds); {
	case n == 0 && len(aggregation) > 0:
		selector.Select(aggregation...)
	case n != 0 && len(aggregation) > 0:
		selector.AppendSelect(aggregation...)
	}
	rows := &sql.Rows{}
	query, args := selector.Query()
	if err := ls.driver.Query(ctx, query, args, rows); err != nil {
		return err
	}
	defer rows.Close()
	return sql.ScanSlice(rows, v)
}
