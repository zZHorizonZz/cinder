package dev.horizon.cinder.contract;

import dev.horizon.cinder.Driver;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;
import java.util.List;

public interface TestDatabase {

  Driver driver();

  Pool pool(Vertx vertx);

  List<String> schema();
}
