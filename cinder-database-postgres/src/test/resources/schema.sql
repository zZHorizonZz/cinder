CREATE TABLE customers (
  id VARCHAR(20) PRIMARY KEY,
  email VARCHAR(100) NOT NULL UNIQUE,
  name VARCHAR(50) NOT NULL
);

DROP TYPE IF EXISTS order_state;

CREATE TYPE order_state AS ENUM ('OPEN', 'PAID', 'SHIPPED', 'CLOSED', 'CANCELLED', 'ARCHIVED', 'LOST');

CREATE TABLE orders (
  id BIGINT PRIMARY KEY,
  customer_id VARCHAR(20) NOT NULL,
  state order_state NOT NULL,
  total_cents BIGINT NOT NULL,
  version INT NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL,
  cancel_time TIMESTAMPTZ
);

CREATE TABLE items (
  id BIGINT PRIMARY KEY,
  order_id BIGINT NOT NULL,
  product VARCHAR(50) NOT NULL,
  quantity INT NOT NULL,
  position INT NOT NULL
);

CREATE TABLE files (
  id BIGINT PRIMARY KEY,
  content BYTEA
);
