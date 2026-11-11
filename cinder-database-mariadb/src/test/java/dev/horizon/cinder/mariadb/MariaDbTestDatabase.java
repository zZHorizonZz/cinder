package dev.horizon.cinder.mariadb;

import dev.horizon.cinder.Driver;
import dev.horizon.cinder.contract.TestDatabase;
import io.vertx.core.Vertx;
import io.vertx.mysqlclient.MySQLBuilder;
import io.vertx.mysqlclient.MySQLConnectOptions;
import io.vertx.sqlclient.Pool;
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
  public boolean storesEnums() {
    return true;
  }
}
