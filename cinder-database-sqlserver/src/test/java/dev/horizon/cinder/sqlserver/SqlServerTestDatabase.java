package dev.horizon.cinder.sqlserver;

import dev.horizon.cinder.Driver;
import dev.horizon.cinder.contract.TestDatabase;
import io.vertx.core.Vertx;
import io.vertx.mssqlclient.MSSQLBuilder;
import io.vertx.mssqlclient.MSSQLConnectOptions;
import io.vertx.sqlclient.Pool;
import java.util.List;
import org.testcontainers.mssqlserver.MSSQLServerContainer;

final class SqlServerTestDatabase implements TestDatabase {

  private static final int PORT = 1433;

  private static final MSSQLServerContainer CONTAINER = new MSSQLServerContainer(
      System.getProperty("cinder.test.sqlserver.image", "mcr.microsoft.com/mssql/server:2022-latest"))
      .acceptLicense();

  @Override
  public Driver driver() {
    return new SqlServerDriver();
  }

  @Override
  public Pool pool(Vertx vertx) {
    synchronized (CONTAINER) {
      if (!CONTAINER.isRunning()) {
        CONTAINER.start();
      }
    }
    return MSSQLBuilder.pool().connectingTo(new MSSQLConnectOptions().setHost(CONTAINER.getHost())
        .setPort(CONTAINER.getMappedPort(PORT)).setUser(CONTAINER.getUsername())
        .setPassword(CONTAINER.getPassword())).using(vertx).build();
  }

  @Override
  public List<String> schema() {
    return List.of(
        "CREATE TABLE customers (id VARCHAR(20) PRIMARY KEY, email VARCHAR(100) NOT NULL UNIQUE,"
            + " name VARCHAR(50) NOT NULL)",
        "CREATE TABLE orders (id BIGINT PRIMARY KEY, customer_id VARCHAR(20) NOT NULL, state VARCHAR(20) NOT NULL,"
            + " total_cents BIGINT NOT NULL, version INT NOT NULL DEFAULT 0, created_at DATETIME2(6) NOT NULL,"
            + " cancel_time DATETIME2(6) NULL)",
        "CREATE TABLE items (id BIGINT PRIMARY KEY, order_id BIGINT NOT NULL, product VARCHAR(50) NOT NULL,"
            + " quantity INT NOT NULL, position INT NOT NULL)",
        "CREATE TABLE files (id BIGINT PRIMARY KEY, content VARBINARY(MAX))");
  }
}
