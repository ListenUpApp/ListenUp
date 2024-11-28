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
	"github.com/ListenUpApp/ListenUp/internal/ent/chapter"
	"github.com/ListenUpApp/ListenUp/internal/ent/predicate"
)

// ChapterQuery is the builder for querying Chapter entities.
type ChapterQuery struct {
	config
	ctx        *QueryContext
	order      []chapter.OrderOption
	inters     []Interceptor
	predicates []predicate.Chapter
	withBook   *BookQuery
	withFKs    bool
	// intermediate query (i.e. traversal path).
	sql  *sql.Selector
	path func(context.Context) (*sql.Selector, error)
}

// Where adds a new predicate for the ChapterQuery builder.
func (cq *ChapterQuery) Where(ps ...predicate.Chapter) *ChapterQuery {
	cq.predicates = append(cq.predicates, ps...)
	return cq
}

// Limit the number of records to be returned by this query.
func (cq *ChapterQuery) Limit(limit int) *ChapterQuery {
	cq.ctx.Limit = &limit
	return cq
}

// Offset to start from.
func (cq *ChapterQuery) Offset(offset int) *ChapterQuery {
	cq.ctx.Offset = &offset
	return cq
}

// Unique configures the query builder to filter duplicate records on query.
// By default, unique is set to true, and can be disabled using this method.
func (cq *ChapterQuery) Unique(unique bool) *ChapterQuery {
	cq.ctx.Unique = &unique
	return cq
}

// Order specifies how the records should be ordered.
func (cq *ChapterQuery) Order(o ...chapter.OrderOption) *ChapterQuery {
	cq.order = append(cq.order, o...)
	return cq
}

// QueryBook chains the current query on the "book" edge.
func (cq *ChapterQuery) QueryBook() *BookQuery {
	query := (&BookClient{config: cq.config}).Query()
	query.path = func(ctx context.Context) (fromU *sql.Selector, err error) {
		if err := cq.prepareQuery(ctx); err != nil {
			return nil, err
		}
		selector := cq.sqlQuery(ctx)
		if err := selector.Err(); err != nil {
			return nil, err
		}
		step := sqlgraph.NewStep(
			sqlgraph.From(chapter.Table, chapter.FieldID, selector),
			sqlgraph.To(book.Table, book.FieldID),
			sqlgraph.Edge(sqlgraph.M2O, true, chapter.BookTable, chapter.BookColumn),
		)
		fromU = sqlgraph.SetNeighbors(cq.driver.Dialect(), step)
		return fromU, nil
	}
	return query
}

// First returns the first Chapter entity from the query.
// Returns a *NotFoundError when no Chapter was found.
func (cq *ChapterQuery) First(ctx context.Context) (*Chapter, error) {
	nodes, err := cq.Limit(1).All(setContextOp(ctx, cq.ctx, ent.OpQueryFirst))
	if err != nil {
		return nil, err
	}
	if len(nodes) == 0 {
		return nil, &NotFoundError{chapter.Label}
	}
	return nodes[0], nil
}

// FirstX is like First, but panics if an error occurs.
func (cq *ChapterQuery) FirstX(ctx context.Context) *Chapter {
	node, err := cq.First(ctx)
	if err != nil && !IsNotFound(err) {
		panic(err)
	}
	return node
}

// FirstID returns the first Chapter ID from the query.
// Returns a *NotFoundError when no Chapter ID was found.
func (cq *ChapterQuery) FirstID(ctx context.Context) (id int, err error) {
	var ids []int
	if ids, err = cq.Limit(1).IDs(setContextOp(ctx, cq.ctx, ent.OpQueryFirstID)); err != nil {
		return
	}
	if len(ids) == 0 {
		err = &NotFoundError{chapter.Label}
		return
	}
	return ids[0], nil
}

// FirstIDX is like FirstID, but panics if an error occurs.
func (cq *ChapterQuery) FirstIDX(ctx context.Context) int {
	id, err := cq.FirstID(ctx)
	if err != nil && !IsNotFound(err) {
		panic(err)
	}
	return id
}

// Only returns a single Chapter entity found by the query, ensuring it only returns one.
// Returns a *NotSingularError when more than one Chapter entity is found.
// Returns a *NotFoundError when no Chapter entities are found.
func (cq *ChapterQuery) Only(ctx context.Context) (*Chapter, error) {
	nodes, err := cq.Limit(2).All(setContextOp(ctx, cq.ctx, ent.OpQueryOnly))
	if err != nil {
		return nil, err
	}
	switch len(nodes) {
	case 1:
		return nodes[0], nil
	case 0:
		return nil, &NotFoundError{chapter.Label}
	default:
		return nil, &NotSingularError{chapter.Label}
	}
}

// OnlyX is like Only, but panics if an error occurs.
func (cq *ChapterQuery) OnlyX(ctx context.Context) *Chapter {
	node, err := cq.Only(ctx)
	if err != nil {
		panic(err)
	}
	return node
}

// OnlyID is like Only, but returns the only Chapter ID in the query.
// Returns a *NotSingularError when more than one Chapter ID is found.
// Returns a *NotFoundError when no entities are found.
func (cq *ChapterQuery) OnlyID(ctx context.Context) (id int, err error) {
	var ids []int
	if ids, err = cq.Limit(2).IDs(setContextOp(ctx, cq.ctx, ent.OpQueryOnlyID)); err != nil {
		return
	}
	switch len(ids) {
	case 1:
		id = ids[0]
	case 0:
		err = &NotFoundError{chapter.Label}
	default:
		err = &NotSingularError{chapter.Label}
	}
	return
}

// OnlyIDX is like OnlyID, but panics if an error occurs.
func (cq *ChapterQuery) OnlyIDX(ctx context.Context) int {
	id, err := cq.OnlyID(ctx)
	if err != nil {
		panic(err)
	}
	return id
}

// All executes the query and returns a list of Chapters.
func (cq *ChapterQuery) All(ctx context.Context) ([]*Chapter, error) {
	ctx = setContextOp(ctx, cq.ctx, ent.OpQueryAll)
	if err := cq.prepareQuery(ctx); err != nil {
		return nil, err
	}
	qr := querierAll[[]*Chapter, *ChapterQuery]()
	return withInterceptors[[]*Chapter](ctx, cq, qr, cq.inters)
}

// AllX is like All, but panics if an error occurs.
func (cq *ChapterQuery) AllX(ctx context.Context) []*Chapter {
	nodes, err := cq.All(ctx)
	if err != nil {
		panic(err)
	}
	return nodes
}

// IDs executes the query and returns a list of Chapter IDs.
func (cq *ChapterQuery) IDs(ctx context.Context) (ids []int, err error) {
	if cq.ctx.Unique == nil && cq.path != nil {
		cq.Unique(true)
	}
	ctx = setContextOp(ctx, cq.ctx, ent.OpQueryIDs)
	if err = cq.Select(chapter.FieldID).Scan(ctx, &ids); err != nil {
		return nil, err
	}
	return ids, nil
}

// IDsX is like IDs, but panics if an error occurs.
func (cq *ChapterQuery) IDsX(ctx context.Context) []int {
	ids, err := cq.IDs(ctx)
	if err != nil {
		panic(err)
	}
	return ids
}

// Count returns the count of the given query.
func (cq *ChapterQuery) Count(ctx context.Context) (int, error) {
	ctx = setContextOp(ctx, cq.ctx, ent.OpQueryCount)
	if err := cq.prepareQuery(ctx); err != nil {
		return 0, err
	}
	return withInterceptors[int](ctx, cq, querierCount[*ChapterQuery](), cq.inters)
}

// CountX is like Count, but panics if an error occurs.
func (cq *ChapterQuery) CountX(ctx context.Context) int {
	count, err := cq.Count(ctx)
	if err != nil {
		panic(err)
	}
	return count
}

// Exist returns true if the query has elements in the graph.
func (cq *ChapterQuery) Exist(ctx context.Context) (bool, error) {
	ctx = setContextOp(ctx, cq.ctx, ent.OpQueryExist)
	switch _, err := cq.FirstID(ctx); {
	case IsNotFound(err):
		return false, nil
	case err != nil:
		return false, fmt.Errorf("ent: check existence: %w", err)
	default:
		return true, nil
	}
}

// ExistX is like Exist, but panics if an error occurs.
func (cq *ChapterQuery) ExistX(ctx context.Context) bool {
	exist, err := cq.Exist(ctx)
	if err != nil {
		panic(err)
	}
	return exist
}

// Clone returns a duplicate of the ChapterQuery builder, including all associated steps. It can be
// used to prepare common query builders and use them differently after the clone is made.
func (cq *ChapterQuery) Clone() *ChapterQuery {
	if cq == nil {
		return nil
	}
	return &ChapterQuery{
		config:     cq.config,
		ctx:        cq.ctx.Clone(),
		order:      append([]chapter.OrderOption{}, cq.order...),
		inters:     append([]Interceptor{}, cq.inters...),
		predicates: append([]predicate.Chapter{}, cq.predicates...),
		withBook:   cq.withBook.Clone(),
		// clone intermediate query.
		sql:  cq.sql.Clone(),
		path: cq.path,
	}
}

// WithBook tells the query-builder to eager-load the nodes that are connected to
// the "book" edge. The optional arguments are used to configure the query builder of the edge.
func (cq *ChapterQuery) WithBook(opts ...func(*BookQuery)) *ChapterQuery {
	query := (&BookClient{config: cq.config}).Query()
	for _, opt := range opts {
		opt(query)
	}
	cq.withBook = query
	return cq
}

// GroupBy is used to group vertices by one or more fields/columns.
// It is often used with aggregate functions, like: count, max, mean, min, sum.
//
// Example:
//
//	var v []struct {
//		Index int `json:"index,omitempty"`
//		Count int `json:"count,omitempty"`
//	}
//
//	client.Chapter.Query().
//		GroupBy(chapter.FieldIndex).
//		Aggregate(ent.Count()).
//		Scan(ctx, &v)
func (cq *ChapterQuery) GroupBy(field string, fields ...string) *ChapterGroupBy {
	cq.ctx.Fields = append([]string{field}, fields...)
	grbuild := &ChapterGroupBy{build: cq}
	grbuild.flds = &cq.ctx.Fields
	grbuild.label = chapter.Label
	grbuild.scan = grbuild.Scan
	return grbuild
}

// Select allows the selection one or more fields/columns for the given query,
// instead of selecting all fields in the entity.
//
// Example:
//
//	var v []struct {
//		Index int `json:"index,omitempty"`
//	}
//
//	client.Chapter.Query().
//		Select(chapter.FieldIndex).
//		Scan(ctx, &v)
func (cq *ChapterQuery) Select(fields ...string) *ChapterSelect {
	cq.ctx.Fields = append(cq.ctx.Fields, fields...)
	sbuild := &ChapterSelect{ChapterQuery: cq}
	sbuild.label = chapter.Label
	sbuild.flds, sbuild.scan = &cq.ctx.Fields, sbuild.Scan
	return sbuild
}

// Aggregate returns a ChapterSelect configured with the given aggregations.
func (cq *ChapterQuery) Aggregate(fns ...AggregateFunc) *ChapterSelect {
	return cq.Select().Aggregate(fns...)
}

func (cq *ChapterQuery) prepareQuery(ctx context.Context) error {
	for _, inter := range cq.inters {
		if inter == nil {
			return fmt.Errorf("ent: uninitialized interceptor (forgotten import ent/runtime?)")
		}
		if trv, ok := inter.(Traverser); ok {
			if err := trv.Traverse(ctx, cq); err != nil {
				return err
			}
		}
	}
	for _, f := range cq.ctx.Fields {
		if !chapter.ValidColumn(f) {
			return &ValidationError{Name: f, err: fmt.Errorf("ent: invalid field %q for query", f)}
		}
	}
	if cq.path != nil {
		prev, err := cq.path(ctx)
		if err != nil {
			return err
		}
		cq.sql = prev
	}
	return nil
}

func (cq *ChapterQuery) sqlAll(ctx context.Context, hooks ...queryHook) ([]*Chapter, error) {
	var (
		nodes       = []*Chapter{}
		withFKs     = cq.withFKs
		_spec       = cq.querySpec()
		loadedTypes = [1]bool{
			cq.withBook != nil,
		}
	)
	if cq.withBook != nil {
		withFKs = true
	}
	if withFKs {
		_spec.Node.Columns = append(_spec.Node.Columns, chapter.ForeignKeys...)
	}
	_spec.ScanValues = func(columns []string) ([]any, error) {
		return (*Chapter).scanValues(nil, columns)
	}
	_spec.Assign = func(columns []string, values []any) error {
		node := &Chapter{config: cq.config}
		nodes = append(nodes, node)
		node.Edges.loadedTypes = loadedTypes
		return node.assignValues(columns, values)
	}
	for i := range hooks {
		hooks[i](ctx, _spec)
	}
	if err := sqlgraph.QueryNodes(ctx, cq.driver, _spec); err != nil {
		return nil, err
	}
	if len(nodes) == 0 {
		return nodes, nil
	}
	if query := cq.withBook; query != nil {
		if err := cq.loadBook(ctx, query, nodes, nil,
			func(n *Chapter, e *Book) { n.Edges.Book = e }); err != nil {
			return nil, err
		}
	}
	return nodes, nil
}

func (cq *ChapterQuery) loadBook(ctx context.Context, query *BookQuery, nodes []*Chapter, init func(*Chapter), assign func(*Chapter, *Book)) error {
	ids := make([]string, 0, len(nodes))
	nodeids := make(map[string][]*Chapter)
	for i := range nodes {
		if nodes[i].book_chapters == nil {
			continue
		}
		fk := *nodes[i].book_chapters
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
			return fmt.Errorf(`unexpected foreign-key "book_chapters" returned %v`, n.ID)
		}
		for i := range nodes {
			assign(nodes[i], n)
		}
	}
	return nil
}

func (cq *ChapterQuery) sqlCount(ctx context.Context) (int, error) {
	_spec := cq.querySpec()
	_spec.Node.Columns = cq.ctx.Fields
	if len(cq.ctx.Fields) > 0 {
		_spec.Unique = cq.ctx.Unique != nil && *cq.ctx.Unique
	}
	return sqlgraph.CountNodes(ctx, cq.driver, _spec)
}

func (cq *ChapterQuery) querySpec() *sqlgraph.QuerySpec {
	_spec := sqlgraph.NewQuerySpec(chapter.Table, chapter.Columns, sqlgraph.NewFieldSpec(chapter.FieldID, field.TypeInt))
	_spec.From = cq.sql
	if unique := cq.ctx.Unique; unique != nil {
		_spec.Unique = *unique
	} else if cq.path != nil {
		_spec.Unique = true
	}
	if fields := cq.ctx.Fields; len(fields) > 0 {
		_spec.Node.Columns = make([]string, 0, len(fields))
		_spec.Node.Columns = append(_spec.Node.Columns, chapter.FieldID)
		for i := range fields {
			if fields[i] != chapter.FieldID {
				_spec.Node.Columns = append(_spec.Node.Columns, fields[i])
			}
		}
	}
	if ps := cq.predicates; len(ps) > 0 {
		_spec.Predicate = func(selector *sql.Selector) {
			for i := range ps {
				ps[i](selector)
			}
		}
	}
	if limit := cq.ctx.Limit; limit != nil {
		_spec.Limit = *limit
	}
	if offset := cq.ctx.Offset; offset != nil {
		_spec.Offset = *offset
	}
	if ps := cq.order; len(ps) > 0 {
		_spec.Order = func(selector *sql.Selector) {
			for i := range ps {
				ps[i](selector)
			}
		}
	}
	return _spec
}

func (cq *ChapterQuery) sqlQuery(ctx context.Context) *sql.Selector {
	builder := sql.Dialect(cq.driver.Dialect())
	t1 := builder.Table(chapter.Table)
	columns := cq.ctx.Fields
	if len(columns) == 0 {
		columns = chapter.Columns
	}
	selector := builder.Select(t1.Columns(columns...)...).From(t1)
	if cq.sql != nil {
		selector = cq.sql
		selector.Select(selector.Columns(columns...)...)
	}
	if cq.ctx.Unique != nil && *cq.ctx.Unique {
		selector.Distinct()
	}
	for _, p := range cq.predicates {
		p(selector)
	}
	for _, p := range cq.order {
		p(selector)
	}
	if offset := cq.ctx.Offset; offset != nil {
		// limit is mandatory for offset clause. We start
		// with default value, and override it below if needed.
		selector.Offset(*offset).Limit(math.MaxInt32)
	}
	if limit := cq.ctx.Limit; limit != nil {
		selector.Limit(*limit)
	}
	return selector
}

// ChapterGroupBy is the group-by builder for Chapter entities.
type ChapterGroupBy struct {
	selector
	build *ChapterQuery
}

// Aggregate adds the given aggregation functions to the group-by query.
func (cgb *ChapterGroupBy) Aggregate(fns ...AggregateFunc) *ChapterGroupBy {
	cgb.fns = append(cgb.fns, fns...)
	return cgb
}

// Scan applies the selector query and scans the result into the given value.
func (cgb *ChapterGroupBy) Scan(ctx context.Context, v any) error {
	ctx = setContextOp(ctx, cgb.build.ctx, ent.OpQueryGroupBy)
	if err := cgb.build.prepareQuery(ctx); err != nil {
		return err
	}
	return scanWithInterceptors[*ChapterQuery, *ChapterGroupBy](ctx, cgb.build, cgb, cgb.build.inters, v)
}

func (cgb *ChapterGroupBy) sqlScan(ctx context.Context, root *ChapterQuery, v any) error {
	selector := root.sqlQuery(ctx).Select()
	aggregation := make([]string, 0, len(cgb.fns))
	for _, fn := range cgb.fns {
		aggregation = append(aggregation, fn(selector))
	}
	if len(selector.SelectedColumns()) == 0 {
		columns := make([]string, 0, len(*cgb.flds)+len(cgb.fns))
		for _, f := range *cgb.flds {
			columns = append(columns, selector.C(f))
		}
		columns = append(columns, aggregation...)
		selector.Select(columns...)
	}
	selector.GroupBy(selector.Columns(*cgb.flds...)...)
	if err := selector.Err(); err != nil {
		return err
	}
	rows := &sql.Rows{}
	query, args := selector.Query()
	if err := cgb.build.driver.Query(ctx, query, args, rows); err != nil {
		return err
	}
	defer rows.Close()
	return sql.ScanSlice(rows, v)
}

// ChapterSelect is the builder for selecting fields of Chapter entities.
type ChapterSelect struct {
	*ChapterQuery
	selector
}

// Aggregate adds the given aggregation functions to the selector query.
func (cs *ChapterSelect) Aggregate(fns ...AggregateFunc) *ChapterSelect {
	cs.fns = append(cs.fns, fns...)
	return cs
}

// Scan applies the selector query and scans the result into the given value.
func (cs *ChapterSelect) Scan(ctx context.Context, v any) error {
	ctx = setContextOp(ctx, cs.ctx, ent.OpQuerySelect)
	if err := cs.prepareQuery(ctx); err != nil {
		return err
	}
	return scanWithInterceptors[*ChapterQuery, *ChapterSelect](ctx, cs.ChapterQuery, cs, cs.inters, v)
}

func (cs *ChapterSelect) sqlScan(ctx context.Context, root *ChapterQuery, v any) error {
	selector := root.sqlQuery(ctx)
	aggregation := make([]string, 0, len(cs.fns))
	for _, fn := range cs.fns {
		aggregation = append(aggregation, fn(selector))
	}
	switch n := len(*cs.selector.flds); {
	case n == 0 && len(aggregation) > 0:
		selector.Select(aggregation...)
	case n != 0 && len(aggregation) > 0:
		selector.AppendSelect(aggregation...)
	}
	rows := &sql.Rows{}
	query, args := selector.Query()
	if err := cs.driver.Query(ctx, query, args, rows); err != nil {
		return err
	}
	defer rows.Close()
	return sql.ScanSlice(rows, v)
}