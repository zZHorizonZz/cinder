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
  public boolean storesEnums() {
    return false;
  }

  @Override
  public boolean locksRows() {
    return false;
  }
}
