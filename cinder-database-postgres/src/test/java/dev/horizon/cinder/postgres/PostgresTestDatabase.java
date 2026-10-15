package dev.horizon.cinder.postgres;

import dev.horizon.cinder.Driver;
import dev.horizon.cinder.contract.TestDatabase;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import java.util.List;
import org.testcontainers.postgresql.PostgreSQLContainer;

final class PostgresTestDatabase implements TestDatabase {

  private static final PostgreSQLContainer CONTAINER = new PostgreSQLContainer(
      System.getProperty("cinder.test.postgres.image", "postgres:17-alpine"));

  @Override
  public Driver driver() {
    return new PostgresDriver();
  }

  @Override
  public Pool pool(Vertx vertx) {
    synchronized (CONTAINER) {
      if (!CONTAINER.isRunning()) {
        CONTAINER.start();
      }
    }
    return PgBuilder.pool().connectingTo(new PgConnectOptions().setHost(CONTAINER.getHost())
        .setPort(CONTAINER.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)).setDatabase(CONTAINER
            .getDatabaseName())
        .setUser(CONTAINER.getUsername()).setPassword(CONTAINER.getPassword()))
        .using(vertx).build();
  }

  @Override
  public List<String> schema() {
    return List.of(
        "CREATE TABLE customers (id VARCHAR(20) PRIMARY KEY, email VARCHAR(100) NOT NULL UNIQUE,"
            + " name VARCHAR(50) NOT NULL)",
        "CREATE TABLE orders (id BIGINT PRIMARY KEY, customer_id VARCHAR(20) NOT NULL, state VARCHAR(20) NOT NULL,"
            + " total_cents BIGINT NOT NULL, version INT NOT NULL DEFAULT 0, created_at TIMESTAMPTZ NOT NULL,"
            + " cancel_time TIMESTAMPTZ)",
        "CREATE TABLE items (id BIGINT PRIMARY KEY, order_id BIGINT NOT NULL, product VARCHAR(50) NOT NULL,"
            + " quantity INT NOT NULL, position INT NOT NULL)",
        "CREATE TABLE files (id BIGINT PRIMARY KEY, content BYTEA)");
  }
}
