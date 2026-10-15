package dev.horizon.cinder.mariadb;

import dev.horizon.cinder.Driver;
import dev.horizon.cinder.contract.TestDatabase;
import io.vertx.core.Vertx;
import io.vertx.mysqlclient.MySQLBuilder;
import io.vertx.mysqlclient.MySQLConnectOptions;
import io.vertx.sqlclient.Pool;
import java.util.List;
import org.testcontainers.mariadb.MariaDBContainer;

final class MariaDbTestDatabase implements TestDatabase {

  private static final int PORT = 3306;

  private static final MariaDBContainer CONTAINER = new MariaDBContainer(
      System.getProperty("cinder.test.mariadb.image", "mariadb:11.8"));

  @Override
  public Driver driver() {
    return new MariaDbDriver();
  }

  @Override
  public Pool pool(Vertx vertx) {
    synchronized (CONTAINER) {
      if (!CONTAINER.isRunning()) {
        CONTAINER.start();
      }
    }
    return MySQLBuilder.pool().connectingTo(new MySQLConnectOptions().setHost(CONTAINER.getHost())
        .setPort(CONTAINER.getMappedPort(PORT)).setDatabase(CONTAINER.getDatabaseName())
        .setUser(CONTAINER.getUsername()).setPassword(CONTAINER.getPassword()).setUseAffectedRows(true)
        .setCachePreparedStatements(true)).using(vertx).build();
  }

  @Override
  public List<String> schema() {
    return List.of(
        "CREATE TABLE customers (id VARCHAR(20) PRIMARY KEY, email VARCHAR(100) NOT NULL UNIQUE,"
            + " name VARCHAR(50) NOT NULL)",
        "CREATE TABLE orders (id BIGINT PRIMARY KEY, customer_id VARCHAR(20) NOT NULL, state VARCHAR(20) NOT NULL,"
            + " total_cents BIGINT NOT NULL, version INT NOT NULL DEFAULT 0, created_at DATETIME(6) NOT NULL,"
            + " cancel_time DATETIME(6) NULL)",
        "CREATE TABLE items (id BIGINT PRIMARY KEY, order_id BIGINT NOT NULL, product VARCHAR(50) NOT NULL,"
            + " quantity INT NOT NULL, position INT NOT NULL)",
        "CREATE TABLE files (id BIGINT PRIMARY KEY, content LONGBLOB)");
  }
}
