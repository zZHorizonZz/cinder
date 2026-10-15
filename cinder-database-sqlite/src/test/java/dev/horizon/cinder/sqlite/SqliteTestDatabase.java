package dev.horizon.cinder.sqlite;

import dev.horizon.cinder.Driver;
import dev.horizon.cinder.contract.TestDatabase;
import io.vertx.core.Vertx;
import io.vertx.jdbcclient.JDBCConnectOptions;
import io.vertx.jdbcclient.JDBCPool;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class SqliteTestDatabase implements TestDatabase {

  @Override
  public Driver driver() {
    return new SqliteDriver();
  }

  @Override
  public Pool pool(Vertx vertx) {
    try {
      Path file = Files.createTempFile("cinder", ".db");
      file.toFile().deleteOnExit();
      return JDBCPool.pool(vertx, new JDBCConnectOptions().setJdbcUrl("jdbc:sqlite:" + file),
          new PoolOptions().setMaxSize(1));
    } catch (IOException failed) {
      throw new UncheckedIOException(failed);
    }
  }

  @Override
  public List<String> schema() {
    return List.of(
        "CREATE TABLE customers (id TEXT PRIMARY KEY, email TEXT NOT NULL UNIQUE, name TEXT NOT NULL)",
        "CREATE TABLE orders (id INTEGER PRIMARY KEY, customer_id TEXT NOT NULL, state TEXT NOT NULL,"
            + " total_cents INTEGER NOT NULL, version INTEGER NOT NULL DEFAULT 0, created_at TEXT NOT NULL,"
            + " cancel_time TEXT)",
        "CREATE TABLE items (id INTEGER PRIMARY KEY, order_id INTEGER NOT NULL, product TEXT NOT NULL,"
            + " quantity INTEGER NOT NULL, position INTEGER NOT NULL)",
        "CREATE TABLE files (id INTEGER PRIMARY KEY, content BLOB)");
  }
}
