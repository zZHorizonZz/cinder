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

CREATE TABLE categories (
  id INTEGER PRIMARY KEY,
  parent_id INTEGER,
  name TEXT NOT NULL
);

CREATE TABLE tags (
  id INTEGER PRIMARY KEY,
  label TEXT NOT NULL
);

CREATE TABLE customer_tags (
  customer_id TEXT NOT NULL,
  tag_id INTEGER NOT NULL,
  PRIMARY KEY (customer_id, tag_id)
);

CREATE TABLE notes (
  id INTEGER PRIMARY KEY,
  body TEXT NOT NULL,
  slug TEXT
);

CREATE TRIGGER notes_slug_insert AFTER INSERT ON notes BEGIN UPDATE notes SET slug = lower(NEW.body) WHERE id = NEW.id; END;

CREATE TRIGGER notes_slug_update AFTER UPDATE OF body ON notes BEGIN UPDATE notes SET slug = lower(NEW.body) WHERE id = NEW.id; END;
