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
	"github.com/ListenUpApp/ListenUp/internal/ent/bookcover"
	"github.com/ListenUpApp/ListenUp/internal/ent/coverversion"
	"github.com/ListenUpApp/ListenUp/internal/ent/predicate"
)

// CoverVersionQuery is the builder for querying CoverVersion entities.
type CoverVersionQuery struct {
	config
	ctx        *QueryContext
	order      []coverversion.OrderOption
	inters     []Interceptor
	predicates []predicate.CoverVersion
	withCover  *BookCoverQuery
	withFKs    bool
	// intermediate query (i.e. traversal path).
	sql  *sql.Selector
	path func(context.Context) (*sql.Selector, error)
}

// Where adds a new predicate for the CoverVersionQuery builder.
func (cvq *CoverVersionQuery) Where(ps ...predicate.CoverVersion) *CoverVersionQuery {
	cvq.predicates = append(cvq.predicates, ps...)
	return cvq
}

// Limit the number of records to be returned by this query.
func (cvq *CoverVersionQuery) Limit(limit int) *CoverVersionQuery {
	cvq.ctx.Limit = &limit
	return cvq
}

// Offset to start from.
func (cvq *CoverVersionQuery) Offset(offset int) *CoverVersionQuery {
	cvq.ctx.Offset = &offset
	return cvq
}

// Unique configures the query builder to filter duplicate records on query.
// By default, unique is set to true, and can be disabled using this method.
func (cvq *CoverVersionQuery) Unique(unique bool) *CoverVersionQuery {
	cvq.ctx.Unique = &unique
	return cvq
}

// Order specifies how the records should be ordered.
func (cvq *CoverVersionQuery) Order(o ...coverversion.OrderOption) *CoverVersionQuery {
	cvq.order = append(cvq.order, o...)
	return cvq
}

// QueryCover chains the current query on the "cover" edge.
func (cvq *CoverVersionQuery) QueryCover() *BookCoverQuery {
	query := (&BookCoverClient{config: cvq.config}).Query()
	query.path = func(ctx context.Context) (fromU *sql.Selector, err error) {
		if err := cvq.prepareQuery(ctx); err != nil {
			return nil, err
		}
		selector := cvq.sqlQuery(ctx)
		if err := selector.Err(); err != nil {
			return nil, err
		}
		step := sqlgraph.NewStep(
			sqlgraph.From(coverversion.Table, coverversion.FieldID, selector),
			sqlgraph.To(bookcover.Table, bookcover.FieldID),
			sqlgraph.Edge(sqlgraph.M2O, true, coverversion.CoverTable, coverversion.CoverColumn),
		)
		fromU = sqlgraph.SetNeighbors(cvq.driver.Dialect(), step)
		return fromU, nil
	}
	return query
}

// First returns the first CoverVersion entity from the query.
// Returns a *NotFoundError when no CoverVersion was found.
func (cvq *CoverVersionQuery) First(ctx context.Context) (*CoverVersion, error) {
	nodes, err := cvq.Limit(1).All(setContextOp(ctx, cvq.ctx, ent.OpQueryFirst))
	if err != nil {
		return nil, err
	}
	if len(nodes) == 0 {
		return nil, &NotFoundError{coverversion.Label}
	}
	return nodes[0], nil
}

// FirstX is like First, but panics if an error occurs.
func (cvq *CoverVersionQuery) FirstX(ctx context.Context) *CoverVersion {
	node, err := cvq.First(ctx)
	if err != nil && !IsNotFound(err) {
		panic(err)
	}
	return node
}

// FirstID returns the first CoverVersion ID from the query.
// Returns a *NotFoundError when no CoverVersion ID was found.
func (cvq *CoverVersionQuery) FirstID(ctx context.Context) (id int, err error) {
	var ids []int
	if ids, err = cvq.Limit(1).IDs(setContextOp(ctx, cvq.ctx, ent.OpQueryFirstID)); err != nil {
		return
	}
	if len(ids) == 0 {
		err = &NotFoundError{coverversion.Label}
		return
	}
	return ids[0], nil
}

// FirstIDX is like FirstID, but panics if an error occurs.
func (cvq *CoverVersionQuery) FirstIDX(ctx context.Context) int {
	id, err := cvq.FirstID(ctx)
	if err != nil && !IsNotFound(err) {
		panic(err)
	}
	return id
}

// Only returns a single CoverVersion entity found by the query, ensuring it only returns one.
// Returns a *NotSingularError when more than one CoverVersion entity is found.
// Returns a *NotFoundError when no CoverVersion entities are found.
func (cvq *CoverVersionQuery) Only(ctx context.Context) (*CoverVersion, error) {
	nodes, err := cvq.Limit(2).All(setContextOp(ctx, cvq.ctx, ent.OpQueryOnly))
	if err != nil {
		return nil, err
	}
	switch len(nodes) {
	case 1:
		return nodes[0], nil
	case 0:
		return nil, &NotFoundError{coverversion.Label}
	default:
		return nil, &NotSingularError{coverversion.Label}
	}
}

// OnlyX is like Only, but panics if an error occurs.
func (cvq *CoverVersionQuery) OnlyX(ctx context.Context) *CoverVersion {
	node, err := cvq.Only(ctx)
	if err != nil {
		panic(err)
	}
	return node
}

// OnlyID is like Only, but returns the only CoverVersion ID in the query.
// Returns a *NotSingularError when more than one CoverVersion ID is found.
// Returns a *NotFoundError when no entities are found.
func (cvq *CoverVersionQuery) OnlyID(ctx context.Context) (id int, err error) {
	var ids []int
	if ids, err = cvq.Limit(2).IDs(setContextOp(ctx, cvq.ctx, ent.OpQueryOnlyID)); err != nil {
		return
	}
	switch len(ids) {
	case 1:
		id = ids[0]
	case 0:
		err = &NotFoundError{coverversion.Label}
	default:
		err = &NotSingularError{coverversion.Label}
	}
	return
}

// OnlyIDX is like OnlyID, but panics if an error occurs.
func (cvq *CoverVersionQuery) OnlyIDX(ctx context.Context) int {
	id, err := cvq.OnlyID(ctx)
	if err != nil {
		panic(err)
	}
	return id
}

// All executes the query and returns a list of CoverVersions.
func (cvq *CoverVersionQuery) All(ctx context.Context) ([]*CoverVersion, error) {
	ctx = setContextOp(ctx, cvq.ctx, ent.OpQueryAll)
	if err := cvq.prepareQuery(ctx); err != nil {
		return nil, err
	}
	qr := querierAll[[]*CoverVersion, *CoverVersionQuery]()
	return withInterceptors[[]*CoverVersion](ctx, cvq, qr, cvq.inters)
}

// AllX is like All, but panics if an error occurs.
func (cvq *CoverVersionQuery) AllX(ctx context.Context) []*CoverVersion {
	nodes, err := cvq.All(ctx)
	if err != nil {
		panic(err)
	}
	return nodes
}

// IDs executes the query and returns a list of CoverVersion IDs.
func (cvq *CoverVersionQuery) IDs(ctx context.Context) (ids []int, err error) {
	if cvq.ctx.Unique == nil && cvq.path != nil {
		cvq.Unique(true)
	}
	ctx = setContextOp(ctx, cvq.ctx, ent.OpQueryIDs)
	if err = cvq.Select(coverversion.FieldID).Scan(ctx, &ids); err != nil {
		return nil, err
	}
	return ids, nil
}

// IDsX is like IDs, but panics if an error occurs.
func (cvq *CoverVersionQuery) IDsX(ctx context.Context) []int {
	ids, err := cvq.IDs(ctx)
	if err != nil {
		panic(err)
	}
	return ids
}

// Count returns the count of the given query.
func (cvq *CoverVersionQuery) Count(ctx context.Context) (int, error) {
	ctx = setContextOp(ctx, cvq.ctx, ent.OpQueryCount)
	if err := cvq.prepareQuery(ctx); err != nil {
		return 0, err
	}
	return withInterceptors[int](ctx, cvq, querierCount[*CoverVersionQuery](), cvq.inters)
}

// CountX is like Count, but panics if an error occurs.
func (cvq *CoverVersionQuery) CountX(ctx context.Context) int {
	count, err := cvq.Count(ctx)
	if err != nil {
		panic(err)
	}
	return count
}

// Exist returns true if the query has elements in the graph.
func (cvq *CoverVersionQuery) Exist(ctx context.Context) (bool, error) {
	ctx = setContextOp(ctx, cvq.ctx, ent.OpQueryExist)
	switch _, err := cvq.FirstID(ctx); {
	case IsNotFound(err):
		return false, nil
	case err != nil:
		return false, fmt.Errorf("ent: check existence: %w", err)
	default:
		return true, nil
	}
}

// ExistX is like Exist, but panics if an error occurs.
func (cvq *CoverVersionQuery) ExistX(ctx context.Context) bool {
	exist, err := cvq.Exist(ctx)
	if err != nil {
		panic(err)
	}
	return exist
}

// Clone returns a duplicate of the CoverVersionQuery builder, including all associated steps. It can be
// used to prepare common query builders and use them differently after the clone is made.
func (cvq *CoverVersionQuery) Clone() *CoverVersionQuery {
	if cvq == nil {
		return nil
	}
	return &CoverVersionQuery{
		config:     cvq.config,
		ctx:        cvq.ctx.Clone(),
		order:      append([]coverversion.OrderOption{}, cvq.order...),
		inters:     append([]Interceptor{}, cvq.inters...),
		predicates: append([]predicate.CoverVersion{}, cvq.predicates...),
		withCover:  cvq.withCover.Clone(),
		// clone intermediate query.
		sql:  cvq.sql.Clone(),
		path: cvq.path,
	}
}

// WithCover tells the query-builder to eager-load the nodes that are connected to
// the "cover" edge. The optional arguments are used to configure the query builder of the edge.
func (cvq *CoverVersionQuery) WithCover(opts ...func(*BookCoverQuery)) *CoverVersionQuery {
	query := (&BookCoverClient{config: cvq.config}).Query()
	for _, opt := range opts {
		opt(query)
	}
	cvq.withCover = query
	return cvq
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
//	client.CoverVersion.Query().
//		GroupBy(coverversion.FieldPath).
//		Aggregate(ent.Count()).
//		Scan(ctx, &v)
func (cvq *CoverVersionQuery) GroupBy(field string, fields ...string) *CoverVersionGroupBy {
	cvq.ctx.Fields = append([]string{field}, fields...)
	grbuild := &CoverVersionGroupBy{build: cvq}
	grbuild.flds = &cvq.ctx.Fields
	grbuild.label = coverversion.Label
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
//	client.CoverVersion.Query().
//		Select(coverversion.FieldPath).
//		Scan(ctx, &v)
func (cvq *CoverVersionQuery) Select(fields ...string) *CoverVersionSelect {
	cvq.ctx.Fields = append(cvq.ctx.Fields, fields...)
	sbuild := &CoverVersionSelect{CoverVersionQuery: cvq}
	sbuild.label = coverversion.Label
	sbuild.flds, sbuild.scan = &cvq.ctx.Fields, sbuild.Scan
	return sbuild
}

// Aggregate returns a CoverVersionSelect configured with the given aggregations.
func (cvq *CoverVersionQuery) Aggregate(fns ...AggregateFunc) *CoverVersionSelect {
	return cvq.Select().Aggregate(fns...)
}

func (cvq *CoverVersionQuery) prepareQuery(ctx context.Context) error {
	for _, inter := range cvq.inters {
		if inter == nil {
			return fmt.Errorf("ent: uninitialized interceptor (forgotten import ent/runtime?)")
		}
		if trv, ok := inter.(Traverser); ok {
			if err := trv.Traverse(ctx, cvq); err != nil {
				return err
			}
		}
	}
	for _, f := range cvq.ctx.Fields {
		if !coverversion.ValidColumn(f) {
			return &ValidationError{Name: f, err: fmt.Errorf("ent: invalid field %q for query", f)}
		}
	}
	if cvq.path != nil {
		prev, err := cvq.path(ctx)
		if err != nil {
			return err
		}
		cvq.sql = prev
	}
	return nil
}

func (cvq *CoverVersionQuery) sqlAll(ctx context.Context, hooks ...queryHook) ([]*CoverVersion, error) {
	var (
		nodes       = []*CoverVersion{}
		withFKs     = cvq.withFKs
		_spec       = cvq.querySpec()
		loadedTypes = [1]bool{
			cvq.withCover != nil,
		}
	)
	if cvq.withCover != nil {
		withFKs = true
	}
	if withFKs {
		_spec.Node.Columns = append(_spec.Node.Columns, coverversion.ForeignKeys...)
	}
	_spec.ScanValues = func(columns []string) ([]any, error) {
		return (*CoverVersion).scanValues(nil, columns)
	}
	_spec.Assign = func(columns []string, values []any) error {
		node := &CoverVersion{config: cvq.config}
		nodes = append(nodes, node)
		node.Edges.loadedTypes = loadedTypes
		return node.assignValues(columns, values)
	}
	for i := range hooks {
		hooks[i](ctx, _spec)
	}
	if err := sqlgraph.QueryNodes(ctx, cvq.driver, _spec); err != nil {
		return nil, err
	}
	if len(nodes) == 0 {
		return nodes, nil
	}
	if query := cvq.withCover; query != nil {
		if err := cvq.loadCover(ctx, query, nodes, nil,
			func(n *CoverVersion, e *BookCover) { n.Edges.Cover = e }); err != nil {
			return nil, err
		}
	}
	return nodes, nil
}

func (cvq *CoverVersionQuery) loadCover(ctx context.Context, query *BookCoverQuery, nodes []*CoverVersion, init func(*CoverVersion), assign func(*CoverVersion, *BookCover)) error {
	ids := make([]int, 0, len(nodes))
	nodeids := make(map[int][]*CoverVersion)
	for i := range nodes {
		if nodes[i].book_cover_versions == nil {
			continue
		}
		fk := *nodes[i].book_cover_versions
		if _, ok := nodeids[fk]; !ok {
			ids = append(ids, fk)
		}
		nodeids[fk] = append(nodeids[fk], nodes[i])
	}
	if len(ids) == 0 {
		return nil
	}
	query.Where(bookcover.IDIn(ids...))
	neighbors, err := query.All(ctx)
	if err != nil {
		return err
	}
	for _, n := range neighbors {
		nodes, ok := nodeids[n.ID]
		if !ok {
			return fmt.Errorf(`unexpected foreign-key "book_cover_versions" returned %v`, n.ID)
		}
		for i := range nodes {
			assign(nodes[i], n)
		}
	}
	return nil
}

func (cvq *CoverVersionQuery) sqlCount(ctx context.Context) (int, error) {
	_spec := cvq.querySpec()
	_spec.Node.Columns = cvq.ctx.Fields
	if len(cvq.ctx.Fields) > 0 {
		_spec.Unique = cvq.ctx.Unique != nil && *cvq.ctx.Unique
	}
	return sqlgraph.CountNodes(ctx, cvq.driver, _spec)
}

func (cvq *CoverVersionQuery) querySpec() *sqlgraph.QuerySpec {
	_spec := sqlgraph.NewQuerySpec(coverversion.Table, coverversion.Columns, sqlgraph.NewFieldSpec(coverversion.FieldID, field.TypeInt))
	_spec.From = cvq.sql
	if unique := cvq.ctx.Unique; unique != nil {
		_spec.Unique = *unique
	} else if cvq.path != nil {
		_spec.Unique = true
	}
	if fields := cvq.ctx.Fields; len(fields) > 0 {
		_spec.Node.Columns = make([]string, 0, len(fields))
		_spec.Node.Columns = append(_spec.Node.Columns, coverversion.FieldID)
		for i := range fields {
			if fields[i] != coverversion.FieldID {
				_spec.Node.Columns = append(_spec.Node.Columns, fields[i])
			}
		}
	}
	if ps := cvq.predicates; len(ps) > 0 {
		_spec.Predicate = func(selector *sql.Selector) {
			for i := range ps {
				ps[i](selector)
			}
		}
	}
	if limit := cvq.ctx.Limit; limit != nil {
		_spec.Limit = *limit
	}
	if offset := cvq.ctx.Offset; offset != nil {
		_spec.Offset = *offset
	}
	if ps := cvq.order; len(ps) > 0 {
		_spec.Order = func(selector *sql.Selector) {
			for i := range ps {
				ps[i](selector)
			}
		}
	}
	return _spec
}

func (cvq *CoverVersionQuery) sqlQuery(ctx context.Context) *sql.Selector {
	builder := sql.Dialect(cvq.driver.Dialect())
	t1 := builder.Table(coverversion.Table)
	columns := cvq.ctx.Fields
	if len(columns) == 0 {
		columns = coverversion.Columns
	}
	selector := builder.Select(t1.Columns(columns...)...).From(t1)
	if cvq.sql != nil {
		selector = cvq.sql
		selector.Select(selector.Columns(columns...)...)
	}
	if cvq.ctx.Unique != nil && *cvq.ctx.Unique {
		selector.Distinct()
	}
	for _, p := range cvq.predicates {
		p(selector)
	}
	for _, p := range cvq.order {
		p(selector)
	}
	if offset := cvq.ctx.Offset; offset != nil {
		// limit is mandatory for offset clause. We start
		// with default value, and override it below if needed.
		selector.Offset(*offset).Limit(math.MaxInt32)
	}
	if limit := cvq.ctx.Limit; limit != nil {
		selector.Limit(*limit)
	}
	return selector
}

// CoverVersionGroupBy is the group-by builder for CoverVersion entities.
type CoverVersionGroupBy struct {
	selector
	build *CoverVersionQuery
}

// Aggregate adds the given aggregation functions to the group-by query.
func (cvgb *CoverVersionGroupBy) Aggregate(fns ...AggregateFunc) *CoverVersionGroupBy {
	cvgb.fns = append(cvgb.fns, fns...)
	return cvgb
}

// Scan applies the selector query and scans the result into the given value.
func (cvgb *CoverVersionGroupBy) Scan(ctx context.Context, v any) error {
	ctx = setContextOp(ctx, cvgb.build.ctx, ent.OpQueryGroupBy)
	if err := cvgb.build.prepareQuery(ctx); err != nil {
		return err
	}
	return scanWithInterceptors[*CoverVersionQuery, *CoverVersionGroupBy](ctx, cvgb.build, cvgb, cvgb.build.inters, v)
}

func (cvgb *CoverVersionGroupBy) sqlScan(ctx context.Context, root *CoverVersionQuery, v any) error {
	selector := root.sqlQuery(ctx).Select()
	aggregation := make([]string, 0, len(cvgb.fns))
	for _, fn := range cvgb.fns {
		aggregation = append(aggregation, fn(selector))
	}
	if len(selector.SelectedColumns()) == 0 {
		columns := make([]string, 0, len(*cvgb.flds)+len(cvgb.fns))
		for _, f := range *cvgb.flds {
			columns = append(columns, selector.C(f))
		}
		columns = append(columns, aggregation...)
		selector.Select(columns...)
	}
	selector.GroupBy(selector.Columns(*cvgb.flds...)...)
	if err := selector.Err(); err != nil {
		return err
	}
	rows := &sql.Rows{}
	query, args := selector.Query()
	if err := cvgb.build.driver.Query(ctx, query, args, rows); err != nil {
		return err
	}
	defer rows.Close()
	return sql.ScanSlice(rows, v)
}

// CoverVersionSelect is the builder for selecting fields of CoverVersion entities.
type CoverVersionSelect struct {
	*CoverVersionQuery
	selector
}

// Aggregate adds the given aggregation functions to the selector query.
func (cvs *CoverVersionSelect) Aggregate(fns ...AggregateFunc) *CoverVersionSelect {
	cvs.fns = append(cvs.fns, fns...)
	return cvs
}

// Scan applies the selector query and scans the result into the given value.
func (cvs *CoverVersionSelect) Scan(ctx context.Context, v any) error {
	ctx = setContextOp(ctx, cvs.ctx, ent.OpQuerySelect)
	if err := cvs.prepareQuery(ctx); err != nil {
		return err
	}
	return scanWithInterceptors[*CoverVersionQuery, *CoverVersionSelect](ctx, cvs.CoverVersionQuery, cvs, cvs.inters, v)
}

func (cvs *CoverVersionSelect) sqlScan(ctx context.Context, root *CoverVersionQuery, v any) error {
	selector := root.sqlQuery(ctx)
	aggregation := make([]string, 0, len(cvs.fns))
	for _, fn := range cvs.fns {
		aggregation = append(aggregation, fn(selector))
	}
	switch n := len(*cvs.selector.flds); {
	case n == 0 && len(aggregation) > 0:
		selector.Select(aggregation...)
	case n != 0 && len(aggregation) > 0:
		selector.AppendSelect(aggregation...)
	}
	rows := &sql.Rows{}
	query, args := selector.Query()
	if err := cvs.driver.Query(ctx, query, args, rows); err != nil {
		return err
	}
	defer rows.Close()
	return sql.ScanSlice(rows, v)
}
