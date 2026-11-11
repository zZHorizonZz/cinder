package dev.horizon.cinder.sqlserver;

import dev.horizon.cinder.Driver;
import dev.horizon.cinder.contract.TestDatabase;
import io.vertx.core.Vertx;
import io.vertx.mssqlclient.MSSQLBuilder;
import io.vertx.mssqlclient.MSSQLConnectOptions;
import io.vertx.sqlclient.Pool;
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
  public boolean storesEnums() {
    return false;
  }

  @Override
  public boolean writesIdentity() {
    return false;
  }
}
