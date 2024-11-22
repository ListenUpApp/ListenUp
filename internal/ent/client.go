// Code generated by ent, DO NOT EDIT.

package ent

import (
	"context"
	"errors"
	"fmt"
	"log"
	"reflect"

	"github.com/ListenUpApp/ListenUp/internal/ent/migrate"

	"entgo.io/ent"
	"entgo.io/ent/dialect"
	"entgo.io/ent/dialect/sql"
	"entgo.io/ent/dialect/sql/sqlgraph"
	"github.com/ListenUpApp/ListenUp/internal/ent/server"
	"github.com/ListenUpApp/ListenUp/internal/ent/serverconfig"
	"github.com/ListenUpApp/ListenUp/internal/ent/user"
)

// Client is the client that holds all ent builders.
type Client struct {
	config
	// Schema is the client for creating, migrating and dropping schema.
	Schema *migrate.Schema
	// Server is the client for interacting with the Server builders.
	Server *ServerClient
	// ServerConfig is the client for interacting with the ServerConfig builders.
	ServerConfig *ServerConfigClient
	// User is the client for interacting with the User builders.
	User *UserClient
}

// NewClient creates a new client configured with the given options.
func NewClient(opts ...Option) *Client {
	client := &Client{config: newConfig(opts...)}
	client.init()
	return client
}

func (c *Client) init() {
	c.Schema = migrate.NewSchema(c.driver)
	c.Server = NewServerClient(c.config)
	c.ServerConfig = NewServerConfigClient(c.config)
	c.User = NewUserClient(c.config)
}

type (
	// config is the configuration for the client and its builder.
	config struct {
		// driver used for executing database requests.
		driver dialect.Driver
		// debug enable a debug logging.
		debug bool
		// log used for logging on debug mode.
		log func(...any)
		// hooks to execute on mutations.
		hooks *hooks
		// interceptors to execute on queries.
		inters *inters
	}
	// Option function to configure the client.
	Option func(*config)
)

// newConfig creates a new config for the client.
func newConfig(opts ...Option) config {
	cfg := config{log: log.Println, hooks: &hooks{}, inters: &inters{}}
	cfg.options(opts...)
	return cfg
}

// options applies the options on the config object.
func (c *config) options(opts ...Option) {
	for _, opt := range opts {
		opt(c)
	}
	if c.debug {
		c.driver = dialect.Debug(c.driver, c.log)
	}
}

// Debug enables debug logging on the ent.Driver.
func Debug() Option {
	return func(c *config) {
		c.debug = true
	}
}

// Log sets the logging function for debug mode.
func Log(fn func(...any)) Option {
	return func(c *config) {
		c.log = fn
	}
}

// Driver configures the client driver.
func Driver(driver dialect.Driver) Option {
	return func(c *config) {
		c.driver = driver
	}
}

// Open opens a database/sql.DB specified by the driver name and
// the data source name, and returns a new client attached to it.
// Optional parameters can be added for configuring the client.
func Open(driverName, dataSourceName string, options ...Option) (*Client, error) {
	switch driverName {
	case dialect.MySQL, dialect.Postgres, dialect.SQLite:
		drv, err := sql.Open(driverName, dataSourceName)
		if err != nil {
			return nil, err
		}
		return NewClient(append(options, Driver(drv))...), nil
	default:
		return nil, fmt.Errorf("unsupported driver: %q", driverName)
	}
}

// ErrTxStarted is returned when trying to start a new transaction from a transactional client.
var ErrTxStarted = errors.New("ent: cannot start a transaction within a transaction")

// Tx returns a new transactional client. The provided context
// is used until the transaction is committed or rolled back.
func (c *Client) Tx(ctx context.Context) (*Tx, error) {
	if _, ok := c.driver.(*txDriver); ok {
		return nil, ErrTxStarted
	}
	tx, err := newTx(ctx, c.driver)
	if err != nil {
		return nil, fmt.Errorf("ent: starting a transaction: %w", err)
	}
	cfg := c.config
	cfg.driver = tx
	return &Tx{
		ctx:          ctx,
		config:       cfg,
		Server:       NewServerClient(cfg),
		ServerConfig: NewServerConfigClient(cfg),
		User:         NewUserClient(cfg),
	}, nil
}

// BeginTx returns a transactional client with specified options.
func (c *Client) BeginTx(ctx context.Context, opts *sql.TxOptions) (*Tx, error) {
	if _, ok := c.driver.(*txDriver); ok {
		return nil, errors.New("ent: cannot start a transaction within a transaction")
	}
	tx, err := c.driver.(interface {
		BeginTx(context.Context, *sql.TxOptions) (dialect.Tx, error)
	}).BeginTx(ctx, opts)
	if err != nil {
		return nil, fmt.Errorf("ent: starting a transaction: %w", err)
	}
	cfg := c.config
	cfg.driver = &txDriver{tx: tx, drv: c.driver}
	return &Tx{
		ctx:          ctx,
		config:       cfg,
		Server:       NewServerClient(cfg),
		ServerConfig: NewServerConfigClient(cfg),
		User:         NewUserClient(cfg),
	}, nil
}

// Debug returns a new debug-client. It's used to get verbose logging on specific operations.
//
//	client.Debug().
//		Server.
//		Query().
//		Count(ctx)
func (c *Client) Debug() *Client {
	if c.debug {
		return c
	}
	cfg := c.config
	cfg.driver = dialect.Debug(c.driver, c.log)
	client := &Client{config: cfg}
	client.init()
	return client
}

// Close closes the database connection and prevents new queries from starting.
func (c *Client) Close() error {
	return c.driver.Close()
}

// Use adds the mutation hooks to all the entity clients.
// In order to add hooks to a specific client, call: `client.Node.Use(...)`.
func (c *Client) Use(hooks ...Hook) {
	c.Server.Use(hooks...)
	c.ServerConfig.Use(hooks...)
	c.User.Use(hooks...)
}

// Intercept adds the query interceptors to all the entity clients.
// In order to add interceptors to a specific client, call: `client.Node.Intercept(...)`.
func (c *Client) Intercept(interceptors ...Interceptor) {
	c.Server.Intercept(interceptors...)
	c.ServerConfig.Intercept(interceptors...)
	c.User.Intercept(interceptors...)
}

// Mutate implements the ent.Mutator interface.
func (c *Client) Mutate(ctx context.Context, m Mutation) (Value, error) {
	switch m := m.(type) {
	case *ServerMutation:
		return c.Server.mutate(ctx, m)
	case *ServerConfigMutation:
		return c.ServerConfig.mutate(ctx, m)
	case *UserMutation:
		return c.User.mutate(ctx, m)
	default:
		return nil, fmt.Errorf("ent: unknown mutation type %T", m)
	}
}

// ServerClient is a client for the Server schema.
type ServerClient struct {
	config
}

// NewServerClient returns a client for the Server from the given config.
func NewServerClient(c config) *ServerClient {
	return &ServerClient{config: c}
}

// Use adds a list of mutation hooks to the hooks stack.
// A call to `Use(f, g, h)` equals to `server.Hooks(f(g(h())))`.
func (c *ServerClient) Use(hooks ...Hook) {
	c.hooks.Server = append(c.hooks.Server, hooks...)
}

// Intercept adds a list of query interceptors to the interceptors stack.
// A call to `Intercept(f, g, h)` equals to `server.Intercept(f(g(h())))`.
func (c *ServerClient) Intercept(interceptors ...Interceptor) {
	c.inters.Server = append(c.inters.Server, interceptors...)
}

// Create returns a builder for creating a Server entity.
func (c *ServerClient) Create() *ServerCreate {
	mutation := newServerMutation(c.config, OpCreate)
	return &ServerCreate{config: c.config, hooks: c.Hooks(), mutation: mutation}
}

// CreateBulk returns a builder for creating a bulk of Server entities.
func (c *ServerClient) CreateBulk(builders ...*ServerCreate) *ServerCreateBulk {
	return &ServerCreateBulk{config: c.config, builders: builders}
}

// MapCreateBulk creates a bulk creation builder from the given slice. For each item in the slice, the function creates
// a builder and applies setFunc on it.
func (c *ServerClient) MapCreateBulk(slice any, setFunc func(*ServerCreate, int)) *ServerCreateBulk {
	rv := reflect.ValueOf(slice)
	if rv.Kind() != reflect.Slice {
		return &ServerCreateBulk{err: fmt.Errorf("calling to ServerClient.MapCreateBulk with wrong type %T, need slice", slice)}
	}
	builders := make([]*ServerCreate, rv.Len())
	for i := 0; i < rv.Len(); i++ {
		builders[i] = c.Create()
		setFunc(builders[i], i)
	}
	return &ServerCreateBulk{config: c.config, builders: builders}
}

// Update returns an update builder for Server.
func (c *ServerClient) Update() *ServerUpdate {
	mutation := newServerMutation(c.config, OpUpdate)
	return &ServerUpdate{config: c.config, hooks: c.Hooks(), mutation: mutation}
}

// UpdateOne returns an update builder for the given entity.
func (c *ServerClient) UpdateOne(s *Server) *ServerUpdateOne {
	mutation := newServerMutation(c.config, OpUpdateOne, withServer(s))
	return &ServerUpdateOne{config: c.config, hooks: c.Hooks(), mutation: mutation}
}

// UpdateOneID returns an update builder for the given id.
func (c *ServerClient) UpdateOneID(id int) *ServerUpdateOne {
	mutation := newServerMutation(c.config, OpUpdateOne, withServerID(id))
	return &ServerUpdateOne{config: c.config, hooks: c.Hooks(), mutation: mutation}
}

// Delete returns a delete builder for Server.
func (c *ServerClient) Delete() *ServerDelete {
	mutation := newServerMutation(c.config, OpDelete)
	return &ServerDelete{config: c.config, hooks: c.Hooks(), mutation: mutation}
}

// DeleteOne returns a builder for deleting the given entity.
func (c *ServerClient) DeleteOne(s *Server) *ServerDeleteOne {
	return c.DeleteOneID(s.ID)
}

// DeleteOneID returns a builder for deleting the given entity by its id.
func (c *ServerClient) DeleteOneID(id int) *ServerDeleteOne {
	builder := c.Delete().Where(server.ID(id))
	builder.mutation.id = &id
	builder.mutation.op = OpDeleteOne
	return &ServerDeleteOne{builder}
}

// Query returns a query builder for Server.
func (c *ServerClient) Query() *ServerQuery {
	return &ServerQuery{
		config: c.config,
		ctx:    &QueryContext{Type: TypeServer},
		inters: c.Interceptors(),
	}
}

// Get returns a Server entity by its id.
func (c *ServerClient) Get(ctx context.Context, id int) (*Server, error) {
	return c.Query().Where(server.ID(id)).Only(ctx)
}

// GetX is like Get, but panics if an error occurs.
func (c *ServerClient) GetX(ctx context.Context, id int) *Server {
	obj, err := c.Get(ctx, id)
	if err != nil {
		panic(err)
	}
	return obj
}

// QueryConfig queries the config edge of a Server.
func (c *ServerClient) QueryConfig(s *Server) *ServerConfigQuery {
	query := (&ServerConfigClient{config: c.config}).Query()
	query.path = func(context.Context) (fromV *sql.Selector, _ error) {
		id := s.ID
		step := sqlgraph.NewStep(
			sqlgraph.From(server.Table, server.FieldID, id),
			sqlgraph.To(serverconfig.Table, serverconfig.FieldID),
			sqlgraph.Edge(sqlgraph.O2O, false, server.ConfigTable, server.ConfigColumn),
		)
		fromV = sqlgraph.Neighbors(s.driver.Dialect(), step)
		return fromV, nil
	}
	return query
}

// Hooks returns the client hooks.
func (c *ServerClient) Hooks() []Hook {
	return c.hooks.Server
}

// Interceptors returns the client interceptors.
func (c *ServerClient) Interceptors() []Interceptor {
	return c.inters.Server
}

func (c *ServerClient) mutate(ctx context.Context, m *ServerMutation) (Value, error) {
	switch m.Op() {
	case OpCreate:
		return (&ServerCreate{config: c.config, hooks: c.Hooks(), mutation: m}).Save(ctx)
	case OpUpdate:
		return (&ServerUpdate{config: c.config, hooks: c.Hooks(), mutation: m}).Save(ctx)
	case OpUpdateOne:
		return (&ServerUpdateOne{config: c.config, hooks: c.Hooks(), mutation: m}).Save(ctx)
	case OpDelete, OpDeleteOne:
		return (&ServerDelete{config: c.config, hooks: c.Hooks(), mutation: m}).Exec(ctx)
	default:
		return nil, fmt.Errorf("ent: unknown Server mutation op: %q", m.Op())
	}
}

// ServerConfigClient is a client for the ServerConfig schema.
type ServerConfigClient struct {
	config
}

// NewServerConfigClient returns a client for the ServerConfig from the given config.
func NewServerConfigClient(c config) *ServerConfigClient {
	return &ServerConfigClient{config: c}
}

// Use adds a list of mutation hooks to the hooks stack.
// A call to `Use(f, g, h)` equals to `serverconfig.Hooks(f(g(h())))`.
func (c *ServerConfigClient) Use(hooks ...Hook) {
	c.hooks.ServerConfig = append(c.hooks.ServerConfig, hooks...)
}

// Intercept adds a list of query interceptors to the interceptors stack.
// A call to `Intercept(f, g, h)` equals to `serverconfig.Intercept(f(g(h())))`.
func (c *ServerConfigClient) Intercept(interceptors ...Interceptor) {
	c.inters.ServerConfig = append(c.inters.ServerConfig, interceptors...)
}

// Create returns a builder for creating a ServerConfig entity.
func (c *ServerConfigClient) Create() *ServerConfigCreate {
	mutation := newServerConfigMutation(c.config, OpCreate)
	return &ServerConfigCreate{config: c.config, hooks: c.Hooks(), mutation: mutation}
}

// CreateBulk returns a builder for creating a bulk of ServerConfig entities.
func (c *ServerConfigClient) CreateBulk(builders ...*ServerConfigCreate) *ServerConfigCreateBulk {
	return &ServerConfigCreateBulk{config: c.config, builders: builders}
}

// MapCreateBulk creates a bulk creation builder from the given slice. For each item in the slice, the function creates
// a builder and applies setFunc on it.
func (c *ServerConfigClient) MapCreateBulk(slice any, setFunc func(*ServerConfigCreate, int)) *ServerConfigCreateBulk {
	rv := reflect.ValueOf(slice)
	if rv.Kind() != reflect.Slice {
		return &ServerConfigCreateBulk{err: fmt.Errorf("calling to ServerConfigClient.MapCreateBulk with wrong type %T, need slice", slice)}
	}
	builders := make([]*ServerConfigCreate, rv.Len())
	for i := 0; i < rv.Len(); i++ {
		builders[i] = c.Create()
		setFunc(builders[i], i)
	}
	return &ServerConfigCreateBulk{config: c.config, builders: builders}
}

// Update returns an update builder for ServerConfig.
func (c *ServerConfigClient) Update() *ServerConfigUpdate {
	mutation := newServerConfigMutation(c.config, OpUpdate)
	return &ServerConfigUpdate{config: c.config, hooks: c.Hooks(), mutation: mutation}
}

// UpdateOne returns an update builder for the given entity.
func (c *ServerConfigClient) UpdateOne(sc *ServerConfig) *ServerConfigUpdateOne {
	mutation := newServerConfigMutation(c.config, OpUpdateOne, withServerConfig(sc))
	return &ServerConfigUpdateOne{config: c.config, hooks: c.Hooks(), mutation: mutation}
}

// UpdateOneID returns an update builder for the given id.
func (c *ServerConfigClient) UpdateOneID(id int) *ServerConfigUpdateOne {
	mutation := newServerConfigMutation(c.config, OpUpdateOne, withServerConfigID(id))
	return &ServerConfigUpdateOne{config: c.config, hooks: c.Hooks(), mutation: mutation}
}

// Delete returns a delete builder for ServerConfig.
func (c *ServerConfigClient) Delete() *ServerConfigDelete {
	mutation := newServerConfigMutation(c.config, OpDelete)
	return &ServerConfigDelete{config: c.config, hooks: c.Hooks(), mutation: mutation}
}

// DeleteOne returns a builder for deleting the given entity.
func (c *ServerConfigClient) DeleteOne(sc *ServerConfig) *ServerConfigDeleteOne {
	return c.DeleteOneID(sc.ID)
}

// DeleteOneID returns a builder for deleting the given entity by its id.
func (c *ServerConfigClient) DeleteOneID(id int) *ServerConfigDeleteOne {
	builder := c.Delete().Where(serverconfig.ID(id))
	builder.mutation.id = &id
	builder.mutation.op = OpDeleteOne
	return &ServerConfigDeleteOne{builder}
}

// Query returns a query builder for ServerConfig.
func (c *ServerConfigClient) Query() *ServerConfigQuery {
	return &ServerConfigQuery{
		config: c.config,
		ctx:    &QueryContext{Type: TypeServerConfig},
		inters: c.Interceptors(),
	}
}

// Get returns a ServerConfig entity by its id.
func (c *ServerConfigClient) Get(ctx context.Context, id int) (*ServerConfig, error) {
	return c.Query().Where(serverconfig.ID(id)).Only(ctx)
}

// GetX is like Get, but panics if an error occurs.
func (c *ServerConfigClient) GetX(ctx context.Context, id int) *ServerConfig {
	obj, err := c.Get(ctx, id)
	if err != nil {
		panic(err)
	}
	return obj
}

// QueryServer queries the server edge of a ServerConfig.
func (c *ServerConfigClient) QueryServer(sc *ServerConfig) *ServerQuery {
	query := (&ServerClient{config: c.config}).Query()
	query.path = func(context.Context) (fromV *sql.Selector, _ error) {
		id := sc.ID
		step := sqlgraph.NewStep(
			sqlgraph.From(serverconfig.Table, serverconfig.FieldID, id),
			sqlgraph.To(server.Table, server.FieldID),
			sqlgraph.Edge(sqlgraph.O2O, true, serverconfig.ServerTable, serverconfig.ServerColumn),
		)
		fromV = sqlgraph.Neighbors(sc.driver.Dialect(), step)
		return fromV, nil
	}
	return query
}

// Hooks returns the client hooks.
func (c *ServerConfigClient) Hooks() []Hook {
	return c.hooks.ServerConfig
}

// Interceptors returns the client interceptors.
func (c *ServerConfigClient) Interceptors() []Interceptor {
	return c.inters.ServerConfig
}

func (c *ServerConfigClient) mutate(ctx context.Context, m *ServerConfigMutation) (Value, error) {
	switch m.Op() {
	case OpCreate:
		return (&ServerConfigCreate{config: c.config, hooks: c.Hooks(), mutation: m}).Save(ctx)
	case OpUpdate:
		return (&ServerConfigUpdate{config: c.config, hooks: c.Hooks(), mutation: m}).Save(ctx)
	case OpUpdateOne:
		return (&ServerConfigUpdateOne{config: c.config, hooks: c.Hooks(), mutation: m}).Save(ctx)
	case OpDelete, OpDeleteOne:
		return (&ServerConfigDelete{config: c.config, hooks: c.Hooks(), mutation: m}).Exec(ctx)
	default:
		return nil, fmt.Errorf("ent: unknown ServerConfig mutation op: %q", m.Op())
	}
}

// UserClient is a client for the User schema.
type UserClient struct {
	config
}

// NewUserClient returns a client for the User from the given config.
func NewUserClient(c config) *UserClient {
	return &UserClient{config: c}
}

// Use adds a list of mutation hooks to the hooks stack.
// A call to `Use(f, g, h)` equals to `user.Hooks(f(g(h())))`.
func (c *UserClient) Use(hooks ...Hook) {
	c.hooks.User = append(c.hooks.User, hooks...)
}

// Intercept adds a list of query interceptors to the interceptors stack.
// A call to `Intercept(f, g, h)` equals to `user.Intercept(f(g(h())))`.
func (c *UserClient) Intercept(interceptors ...Interceptor) {
	c.inters.User = append(c.inters.User, interceptors...)
}

// Create returns a builder for creating a User entity.
func (c *UserClient) Create() *UserCreate {
	mutation := newUserMutation(c.config, OpCreate)
	return &UserCreate{config: c.config, hooks: c.Hooks(), mutation: mutation}
}

// CreateBulk returns a builder for creating a bulk of User entities.
func (c *UserClient) CreateBulk(builders ...*UserCreate) *UserCreateBulk {
	return &UserCreateBulk{config: c.config, builders: builders}
}

// MapCreateBulk creates a bulk creation builder from the given slice. For each item in the slice, the function creates
// a builder and applies setFunc on it.
func (c *UserClient) MapCreateBulk(slice any, setFunc func(*UserCreate, int)) *UserCreateBulk {
	rv := reflect.ValueOf(slice)
	if rv.Kind() != reflect.Slice {
		return &UserCreateBulk{err: fmt.Errorf("calling to UserClient.MapCreateBulk with wrong type %T, need slice", slice)}
	}
	builders := make([]*UserCreate, rv.Len())
	for i := 0; i < rv.Len(); i++ {
		builders[i] = c.Create()
		setFunc(builders[i], i)
	}
	return &UserCreateBulk{config: c.config, builders: builders}
}

// Update returns an update builder for User.
func (c *UserClient) Update() *UserUpdate {
	mutation := newUserMutation(c.config, OpUpdate)
	return &UserUpdate{config: c.config, hooks: c.Hooks(), mutation: mutation}
}

// UpdateOne returns an update builder for the given entity.
func (c *UserClient) UpdateOne(u *User) *UserUpdateOne {
	mutation := newUserMutation(c.config, OpUpdateOne, withUser(u))
	return &UserUpdateOne{config: c.config, hooks: c.Hooks(), mutation: mutation}
}

// UpdateOneID returns an update builder for the given id.
func (c *UserClient) UpdateOneID(id string) *UserUpdateOne {
	mutation := newUserMutation(c.config, OpUpdateOne, withUserID(id))
	return &UserUpdateOne{config: c.config, hooks: c.Hooks(), mutation: mutation}
}

// Delete returns a delete builder for User.
func (c *UserClient) Delete() *UserDelete {
	mutation := newUserMutation(c.config, OpDelete)
	return &UserDelete{config: c.config, hooks: c.Hooks(), mutation: mutation}
}

// DeleteOne returns a builder for deleting the given entity.
func (c *UserClient) DeleteOne(u *User) *UserDeleteOne {
	return c.DeleteOneID(u.ID)
}

// DeleteOneID returns a builder for deleting the given entity by its id.
func (c *UserClient) DeleteOneID(id string) *UserDeleteOne {
	builder := c.Delete().Where(user.ID(id))
	builder.mutation.id = &id
	builder.mutation.op = OpDeleteOne
	return &UserDeleteOne{builder}
}

// Query returns a query builder for User.
func (c *UserClient) Query() *UserQuery {
	return &UserQuery{
		config: c.config,
		ctx:    &QueryContext{Type: TypeUser},
		inters: c.Interceptors(),
	}
}

// Get returns a User entity by its id.
func (c *UserClient) Get(ctx context.Context, id string) (*User, error) {
	return c.Query().Where(user.ID(id)).Only(ctx)
}

// GetX is like Get, but panics if an error occurs.
func (c *UserClient) GetX(ctx context.Context, id string) *User {
	obj, err := c.Get(ctx, id)
	if err != nil {
		panic(err)
	}
	return obj
}

// Hooks returns the client hooks.
func (c *UserClient) Hooks() []Hook {
	return c.hooks.User
}

// Interceptors returns the client interceptors.
func (c *UserClient) Interceptors() []Interceptor {
	return c.inters.User
}

func (c *UserClient) mutate(ctx context.Context, m *UserMutation) (Value, error) {
	switch m.Op() {
	case OpCreate:
		return (&UserCreate{config: c.config, hooks: c.Hooks(), mutation: m}).Save(ctx)
	case OpUpdate:
		return (&UserUpdate{config: c.config, hooks: c.Hooks(), mutation: m}).Save(ctx)
	case OpUpdateOne:
		return (&UserUpdateOne{config: c.config, hooks: c.Hooks(), mutation: m}).Save(ctx)
	case OpDelete, OpDeleteOne:
		return (&UserDelete{config: c.config, hooks: c.Hooks(), mutation: m}).Exec(ctx)
	default:
		return nil, fmt.Errorf("ent: unknown User mutation op: %q", m.Op())
	}
}

// hooks and interceptors per client, for fast access.
type (
	hooks struct {
		Server, ServerConfig, User []ent.Hook
	}
	inters struct {
		Server, ServerConfig, User []ent.Interceptor
	}
)
