CREATE TABLE customers (
  id TEXT PRIMARY KEY,
  email TEXT NOT NULL UNIQUE,
  name TEXT NOT NULL
);

CREATE TABLE orders (
  id INTEGER PRIMARY KEY,
  customer_id TEXT NOT NULL,
  state TEXT NOT NULL,
  total_cents INTEGER NOT NULL,
  version INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL,
  cancel_time TEXT
);

CREATE TABLE items (
  id INTEGER PRIMARY KEY,
  order_id INTEGER NOT NULL,
  product TEXT NOT NULL,
  quantity INTEGER NOT NULL,
  position INTEGER NOT NULL
);

CREATE TABLE files (
  id INTEGER PRIMARY KEY,
  content BLOB
);
