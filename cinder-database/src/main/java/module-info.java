module dev.horizon.cinder {
  requires transitive dev.horizon.basepoint;
  requires transitive io.vertx.core;
  requires transitive io.vertx.sql.client;

  exports dev.horizon.cinder;

  provides io.vertx.core.spi.VertxServiceProvider with dev.horizon.cinder.impl.TransactionLocal;
}
