package dev.horizon.cinder.postgres;

import dev.horizon.cinder.Driver;
import dev.horizon.cinder.contract.TestDatabase;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
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
  public boolean storesEnums() {
    return true;
  }
}
