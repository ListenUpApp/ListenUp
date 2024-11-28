// Code generated by ent, DO NOT EDIT.

package ent

import (
	"context"
	"fmt"
	"math"

	"entgo.io/ent"
	"entgo.io/ent/dialect/sql"
	"entgo.io/ent/dialect/sql/sqlgraph"
	"entgo.io/ent/schema/field"
	"github.com/ListenUpApp/ListenUp/internal/ent/book"
	"github.com/ListenUpApp/ListenUp/internal/ent/bookcover"
	"github.com/ListenUpApp/ListenUp/internal/ent/predicate"
)

// BookCoverQuery is the builder for querying BookCover entities.
type BookCoverQuery struct {
	config
	ctx        *QueryContext
	order      []bookcover.OrderOption
	inters     []Interceptor
	predicates []predicate.BookCover
	withBook   *BookQuery
	withFKs    bool
	// intermediate query (i.e. traversal path).
	sql  *sql.Selector
	path func(context.Context) (*sql.Selector, error)
}

// Where adds a new predicate for the BookCoverQuery builder.
func (bcq *BookCoverQuery) Where(ps ...predicate.BookCover) *BookCoverQuery {
	bcq.predicates = append(bcq.predicates, ps...)
	return bcq
}

// Limit the number of records to be returned by this query.
func (bcq *BookCoverQuery) Limit(limit int) *BookCoverQuery {
	bcq.ctx.Limit = &limit
	return bcq
}

// Offset to start from.
func (bcq *BookCoverQuery) Offset(offset int) *BookCoverQuery {
	bcq.ctx.Offset = &offset
	return bcq
}

// Unique configures the query builder to filter duplicate records on query.
// By default, unique is set to true, and can be disabled using this method.
func (bcq *BookCoverQuery) Unique(unique bool) *BookCoverQuery {
	bcq.ctx.Unique = &unique
	return bcq
}

// Order specifies how the records should be ordered.
func (bcq *BookCoverQuery) Order(o ...bookcover.OrderOption) *BookCoverQuery {
	bcq.order = append(bcq.order, o...)
	return bcq
}

// QueryBook chains the current query on the "book" edge.
func (bcq *BookCoverQuery) QueryBook() *BookQuery {
	query := (&BookClient{config: bcq.config}).Query()
	query.path = func(ctx context.Context) (fromU *sql.Selector, err error) {
		if err := bcq.prepareQuery(ctx); err != nil {
			return nil, err
		}
		selector := bcq.sqlQuery(ctx)
		if err := selector.Err(); err != nil {
			return nil, err
		}
		step := sqlgraph.NewStep(
			sqlgraph.From(bookcover.Table, bookcover.FieldID, selector),
			sqlgraph.To(book.Table, book.FieldID),
			sqlgraph.Edge(sqlgraph.O2O, true, bookcover.BookTable, bookcover.BookColumn),
		)
		fromU = sqlgraph.SetNeighbors(bcq.driver.Dialect(), step)
		return fromU, nil
	}
	return query
}

// First returns the first BookCover entity from the query.
// Returns a *NotFoundError when no BookCover was found.
func (bcq *BookCoverQuery) First(ctx context.Context) (*BookCover, error) {
	nodes, err := bcq.Limit(1).All(setContextOp(ctx, bcq.ctx, ent.OpQueryFirst))
	if err != nil {
		return nil, err
	}
	if len(nodes) == 0 {
		return nil, &NotFoundError{bookcover.Label}
	}
	return nodes[0], nil
}

// FirstX is like First, but panics if an error occurs.
func (bcq *BookCoverQuery) FirstX(ctx context.Context) *BookCover {
	node, err := bcq.First(ctx)
	if err != nil && !IsNotFound(err) {
		panic(err)
	}
	return node
}

// FirstID returns the first BookCover ID from the query.
// Returns a *NotFoundError when no BookCover ID was found.
func (bcq *BookCoverQuery) FirstID(ctx context.Context) (id int, err error) {
	var ids []int
	if ids, err = bcq.Limit(1).IDs(setContextOp(ctx, bcq.ctx, ent.OpQueryFirstID)); err != nil {
		return
	}
	if len(ids) == 0 {
		err = &NotFoundError{bookcover.Label}
		return
	}
	return ids[0], nil
}

// FirstIDX is like FirstID, but panics if an error occurs.
func (bcq *BookCoverQuery) FirstIDX(ctx context.Context) int {
	id, err := bcq.FirstID(ctx)
	if err != nil && !IsNotFound(err) {
		panic(err)
	}
	return id
}

// Only returns a single BookCover entity found by the query, ensuring it only returns one.
// Returns a *NotSingularError when more than one BookCover entity is found.
// Returns a *NotFoundError when no BookCover entities are found.
func (bcq *BookCoverQuery) Only(ctx context.Context) (*BookCover, error) {
	nodes, err := bcq.Limit(2).All(setContextOp(ctx, bcq.ctx, ent.OpQueryOnly))
	if err != nil {
		return nil, err
	}
	switch len(nodes) {
	case 1:
		return nodes[0], nil
	case 0:
		return nil, &NotFoundError{bookcover.Label}
	default:
		return nil, &NotSingularError{bookcover.Label}
	}
}

// OnlyX is like Only, but panics if an error occurs.
func (bcq *BookCoverQuery) OnlyX(ctx context.Context) *BookCover {
	node, err := bcq.Only(ctx)
	if err != nil {
		panic(err)
	}
	return node
}

// OnlyID is like Only, but returns the only BookCover ID in the query.
// Returns a *NotSingularError when more than one BookCover ID is found.
// Returns a *NotFoundError when no entities are found.
func (bcq *BookCoverQuery) OnlyID(ctx context.Context) (id int, err error) {
	var ids []int
	if ids, err = bcq.Limit(2).IDs(setContextOp(ctx, bcq.ctx, ent.OpQueryOnlyID)); err != nil {
		return
	}
	switch len(ids) {
	case 1:
		id = ids[0]
	case 0:
		err = &NotFoundError{bookcover.Label}
	default:
		err = &NotSingularError{bookcover.Label}
	}
	return
}

// OnlyIDX is like OnlyID, but panics if an error occurs.
func (bcq *BookCoverQuery) OnlyIDX(ctx context.Context) int {
	id, err := bcq.OnlyID(ctx)
	if err != nil {
		panic(err)
	}
	return id
}

// All executes the query and returns a list of BookCovers.
func (bcq *BookCoverQuery) All(ctx context.Context) ([]*BookCover, error) {
	ctx = setContextOp(ctx, bcq.ctx, ent.OpQueryAll)
	if err := bcq.prepareQuery(ctx); err != nil {
		return nil, err
	}
	qr := querierAll[[]*BookCover, *BookCoverQuery]()
	return withInterceptors[[]*BookCover](ctx, bcq, qr, bcq.inters)
}

// AllX is like All, but panics if an error occurs.
func (bcq *BookCoverQuery) AllX(ctx context.Context) []*BookCover {
	nodes, err := bcq.All(ctx)
	if err != nil {
		panic(err)
	}
	return nodes
}

// IDs executes the query and returns a list of BookCover IDs.
func (bcq *BookCoverQuery) IDs(ctx context.Context) (ids []int, err error) {
	if bcq.ctx.Unique == nil && bcq.path != nil {
		bcq.Unique(true)
	}
	ctx = setContextOp(ctx, bcq.ctx, ent.OpQueryIDs)
	if err = bcq.Select(bookcover.FieldID).Scan(ctx, &ids); err != nil {
		return nil, err
	}
	return ids, nil
}

// IDsX is like IDs, but panics if an error occurs.
func (bcq *BookCoverQuery) IDsX(ctx context.Context) []int {
	ids, err := bcq.IDs(ctx)
	if err != nil {
		panic(err)
	}
	return ids
}

// Count returns the count of the given query.
func (bcq *BookCoverQuery) Count(ctx context.Context) (int, error) {
	ctx = setContextOp(ctx, bcq.ctx, ent.OpQueryCount)
	if err := bcq.prepareQuery(ctx); err != nil {
		return 0, err
	}
	return withInterceptors[int](ctx, bcq, querierCount[*BookCoverQuery](), bcq.inters)
}

// CountX is like Count, but panics if an error occurs.
func (bcq *BookCoverQuery) CountX(ctx context.Context) int {
	count, err := bcq.Count(ctx)
	if err != nil {
		panic(err)
	}
	return count
}

// Exist returns true if the query has elements in the graph.
func (bcq *BookCoverQuery) Exist(ctx context.Context) (bool, error) {
	ctx = setContextOp(ctx, bcq.ctx, ent.OpQueryExist)
	switch _, err := bcq.FirstID(ctx); {
	case IsNotFound(err):
		return false, nil
	case err != nil:
		return false, fmt.Errorf("ent: check existence: %w", err)
	default:
		return true, nil
	}
}

// ExistX is like Exist, but panics if an error occurs.
func (bcq *BookCoverQuery) ExistX(ctx context.Context) bool {
	exist, err := bcq.Exist(ctx)
	if err != nil {
		panic(err)
	}
	return exist
}

// Clone returns a duplicate of the BookCoverQuery builder, including all associated steps. It can be
// used to prepare common query builders and use them differently after the clone is made.
func (bcq *BookCoverQuery) Clone() *BookCoverQuery {
	if bcq == nil {
		return nil
	}
	return &BookCoverQuery{
		config:     bcq.config,
		ctx:        bcq.ctx.Clone(),
		order:      append([]bookcover.OrderOption{}, bcq.order...),
		inters:     append([]Interceptor{}, bcq.inters...),
		predicates: append([]predicate.BookCover{}, bcq.predicates...),
		withBook:   bcq.withBook.Clone(),
		// clone intermediate query.
		sql:  bcq.sql.Clone(),
		path: bcq.path,
	}
}

// WithBook tells the query-builder to eager-load the nodes that are connected to
// the "book" edge. The optional arguments are used to configure the query builder of the edge.
func (bcq *BookCoverQuery) WithBook(opts ...func(*BookQuery)) *BookCoverQuery {
	query := (&BookClient{config: bcq.config}).Query()
	for _, opt := range opts {
		opt(query)
	}
	bcq.withBook = query
	return bcq
}

// GroupBy is used to group vertices by one or more fields/columns.
// It is often used with aggregate functions, like: count, max, mean, min, sum.
//
// Example:
//
//	var v []struct {
//		Path string `json:"path,omitempty"`
//		Count int `json:"count,omitempty"`
//	}
//
//	client.BookCover.Query().
//		GroupBy(bookcover.FieldPath).
//		Aggregate(ent.Count()).
//		Scan(ctx, &v)
func (bcq *BookCoverQuery) GroupBy(field string, fields ...string) *BookCoverGroupBy {
	bcq.ctx.Fields = append([]string{field}, fields...)
	grbuild := &BookCoverGroupBy{build: bcq}
	grbuild.flds = &bcq.ctx.Fields
	grbuild.label = bookcover.Label
	grbuild.scan = grbuild.Scan
	return grbuild
}

// Select allows the selection one or more fields/columns for the given query,
// instead of selecting all fields in the entity.
//
// Example:
//
//	var v []struct {
//		Path string `json:"path,omitempty"`
//	}
//
//	client.BookCover.Query().
//		Select(bookcover.FieldPath).
//		Scan(ctx, &v)
func (bcq *BookCoverQuery) Select(fields ...string) *BookCoverSelect {
	bcq.ctx.Fields = append(bcq.ctx.Fields, fields...)
	sbuild := &BookCoverSelect{BookCoverQuery: bcq}
	sbuild.label = bookcover.Label
	sbuild.flds, sbuild.scan = &bcq.ctx.Fields, sbuild.Scan
	return sbuild
}

// Aggregate returns a BookCoverSelect configured with the given aggregations.
func (bcq *BookCoverQuery) Aggregate(fns ...AggregateFunc) *BookCoverSelect {
	return bcq.Select().Aggregate(fns...)
}

func (bcq *BookCoverQuery) prepareQuery(ctx context.Context) error {
	for _, inter := range bcq.inters {
		if inter == nil {
			return fmt.Errorf("ent: uninitialized interceptor (forgotten import ent/runtime?)")
		}
		if trv, ok := inter.(Traverser); ok {
			if err := trv.Traverse(ctx, bcq); err != nil {
				return err
			}
		}
	}
	for _, f := range bcq.ctx.Fields {
		if !bookcover.ValidColumn(f) {
			return &ValidationError{Name: f, err: fmt.Errorf("ent: invalid field %q for query", f)}
		}
	}
	if bcq.path != nil {
		prev, err := bcq.path(ctx)
		if err != nil {
			return err
		}
		bcq.sql = prev
	}
	return nil
}

func (bcq *BookCoverQuery) sqlAll(ctx context.Context, hooks ...queryHook) ([]*BookCover, error) {
	var (
		nodes       = []*BookCover{}
		withFKs     = bcq.withFKs
		_spec       = bcq.querySpec()
		loadedTypes = [1]bool{
			bcq.withBook != nil,
		}
	)
	if bcq.withBook != nil {
		withFKs = true
	}
	if withFKs {
		_spec.Node.Columns = append(_spec.Node.Columns, bookcover.ForeignKeys...)
	}
	_spec.ScanValues = func(columns []string) ([]any, error) {
		return (*BookCover).scanValues(nil, columns)
	}
	_spec.Assign = func(columns []string, values []any) error {
		node := &BookCover{config: bcq.config}
		nodes = append(nodes, node)
		node.Edges.loadedTypes = loadedTypes
		return node.assignValues(columns, values)
	}
	for i := range hooks {
		hooks[i](ctx, _spec)
	}
	if err := sqlgraph.QueryNodes(ctx, bcq.driver, _spec); err != nil {
		return nil, err
	}
	if len(nodes) == 0 {
		return nodes, nil
	}
	if query := bcq.withBook; query != nil {
		if err := bcq.loadBook(ctx, query, nodes, nil,
			func(n *BookCover, e *Book) { n.Edges.Book = e }); err != nil {
			return nil, err
		}
	}
	return nodes, nil
}

func (bcq *BookCoverQuery) loadBook(ctx context.Context, query *BookQuery, nodes []*BookCover, init func(*BookCover), assign func(*BookCover, *Book)) error {
	ids := make([]string, 0, len(nodes))
	nodeids := make(map[string][]*BookCover)
	for i := range nodes {
		if nodes[i].book_cover == nil {
			continue
		}
		fk := *nodes[i].book_cover
		if _, ok := nodeids[fk]; !ok {
			ids = append(ids, fk)
		}
		nodeids[fk] = append(nodeids[fk], nodes[i])
	}
	if len(ids) == 0 {
		return nil
	}
	query.Where(book.IDIn(ids...))
	neighbors, err := query.All(ctx)
	if err != nil {
		return err
	}
	for _, n := range neighbors {
		nodes, ok := nodeids[n.ID]
		if !ok {
			return fmt.Errorf(`unexpected foreign-key "book_cover" returned %v`, n.ID)
		}
		for i := range nodes {
			assign(nodes[i], n)
		}
	}
	return nil
}

func (bcq *BookCoverQuery) sqlCount(ctx context.Context) (int, error) {
	_spec := bcq.querySpec()
	_spec.Node.Columns = bcq.ctx.Fields
	if len(bcq.ctx.Fields) > 0 {
		_spec.Unique = bcq.ctx.Unique != nil && *bcq.ctx.Unique
	}
	return sqlgraph.CountNodes(ctx, bcq.driver, _spec)
}

func (bcq *BookCoverQuery) querySpec() *sqlgraph.QuerySpec {
	_spec := sqlgraph.NewQuerySpec(bookcover.Table, bookcover.Columns, sqlgraph.NewFieldSpec(bookcover.FieldID, field.TypeInt))
	_spec.From = bcq.sql
	if unique := bcq.ctx.Unique; unique != nil {
		_spec.Unique = *unique
	} else if bcq.path != nil {
		_spec.Unique = true
	}
	if fields := bcq.ctx.Fields; len(fields) > 0 {
		_spec.Node.Columns = make([]string, 0, len(fields))
		_spec.Node.Columns = append(_spec.Node.Columns, bookcover.FieldID)
		for i := range fields {
			if fields[i] != bookcover.FieldID {
				_spec.Node.Columns = append(_spec.Node.Columns, fields[i])
			}
		}
	}
	if ps := bcq.predicates; len(ps) > 0 {
		_spec.Predicate = func(selector *sql.Selector) {
			for i := range ps {
				ps[i](selector)
			}
		}
	}
	if limit := bcq.ctx.Limit; limit != nil {
		_spec.Limit = *limit
	}
	if offset := bcq.ctx.Offset; offset != nil {
		_spec.Offset = *offset
	}
	if ps := bcq.order; len(ps) > 0 {
		_spec.Order = func(selector *sql.Selector) {
			for i := range ps {
				ps[i](selector)
			}
		}
	}
	return _spec
}

func (bcq *BookCoverQuery) sqlQuery(ctx context.Context) *sql.Selector {
	builder := sql.Dialect(bcq.driver.Dialect())
	t1 := builder.Table(bookcover.Table)
	columns := bcq.ctx.Fields
	if len(columns) == 0 {
		columns = bookcover.Columns
	}
	selector := builder.Select(t1.Columns(columns...)...).From(t1)
	if bcq.sql != nil {
		selector = bcq.sql
		selector.Select(selector.Columns(columns...)...)
	}
	if bcq.ctx.Unique != nil && *bcq.ctx.Unique {
		selector.Distinct()
	}
	for _, p := range bcq.predicates {
		p(selector)
	}
	for _, p := range bcq.order {
		p(selector)
	}
	if offset := bcq.ctx.Offset; offset != nil {
		// limit is mandatory for offset clause. We start
		// with default value, and override it below if needed.
		selector.Offset(*offset).Limit(math.MaxInt32)
	}
	if limit := bcq.ctx.Limit; limit != nil {
		selector.Limit(*limit)
	}
	return selector
}

// BookCoverGroupBy is the group-by builder for BookCover entities.
type BookCoverGroupBy struct {
	selector
	build *BookCoverQuery
}

// Aggregate adds the given aggregation functions to the group-by query.
func (bcgb *BookCoverGroupBy) Aggregate(fns ...AggregateFunc) *BookCoverGroupBy {
	bcgb.fns = append(bcgb.fns, fns...)
	return bcgb
}

// Scan applies the selector query and scans the result into the given value.
func (bcgb *BookCoverGroupBy) Scan(ctx context.Context, v any) error {
	ctx = setContextOp(ctx, bcgb.build.ctx, ent.OpQueryGroupBy)
	if err := bcgb.build.prepareQuery(ctx); err != nil {
		return err
	}
	return scanWithInterceptors[*BookCoverQuery, *BookCoverGroupBy](ctx, bcgb.build, bcgb, bcgb.build.inters, v)
}

func (bcgb *BookCoverGroupBy) sqlScan(ctx context.Context, root *BookCoverQuery, v any) error {
	selector := root.sqlQuery(ctx).Select()
	aggregation := make([]string, 0, len(bcgb.fns))
	for _, fn := range bcgb.fns {
		aggregation = append(aggregation, fn(selector))
	}
	if len(selector.SelectedColumns()) == 0 {
		columns := make([]string, 0, len(*bcgb.flds)+len(bcgb.fns))
		for _, f := range *bcgb.flds {
			columns = append(columns, selector.C(f))
		}
		columns = append(columns, aggregation...)
		selector.Select(columns...)
	}
	selector.GroupBy(selector.Columns(*bcgb.flds...)...)
	if err := selector.Err(); err != nil {
		return err
	}
	rows := &sql.Rows{}
	query, args := selector.Query()
	if err := bcgb.build.driver.Query(ctx, query, args, rows); err != nil {
		return err
	}
	defer rows.Close()
	return sql.ScanSlice(rows, v)
}

// BookCoverSelect is the builder for selecting fields of BookCover entities.
type BookCoverSelect struct {
	*BookCoverQuery
	selector
}

// Aggregate adds the given aggregation functions to the selector query.
func (bcs *BookCoverSelect) Aggregate(fns ...AggregateFunc) *BookCoverSelect {
	bcs.fns = append(bcs.fns, fns...)
	return bcs
}

// Scan applies the selector query and scans the result into the given value.
func (bcs *BookCoverSelect) Scan(ctx context.Context, v any) error {
	ctx = setContextOp(ctx, bcs.ctx, ent.OpQuerySelect)
	if err := bcs.prepareQuery(ctx); err != nil {
		return err
	}
	return scanWithInterceptors[*BookCoverQuery, *BookCoverSelect](ctx, bcs.BookCoverQuery, bcs, bcs.inters, v)
}

func (bcs *BookCoverSelect) sqlScan(ctx context.Context, root *BookCoverQuery, v any) error {
	selector := root.sqlQuery(ctx)
	aggregation := make([]string, 0, len(bcs.fns))
	for _, fn := range bcs.fns {
		aggregation = append(aggregation, fn(selector))
	}
	switch n := len(*bcs.selector.flds); {
	case n == 0 && len(aggregation) > 0:
		selector.Select(aggregation...)
	case n != 0 && len(aggregation) > 0:
		selector.AppendSelect(aggregation...)
	}
	rows := &sql.Rows{}
	query, args := selector.Query()
	if err := bcs.driver.Query(ctx, query, args, rows); err != nil {
		return err
	}
	defer rows.Close()
	return sql.ScanSlice(rows, v)
}
