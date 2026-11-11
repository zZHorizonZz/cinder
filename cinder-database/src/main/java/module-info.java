/**
 * Cinder: the rows of a database as entities, on the SQL clients of Vert.x and the statements of Basepoint.
 */
module dev.horizon.cinder {
  requires transitive dev.horizon.basepoint;
  requires transitive io.vertx.core;
  requires transitive io.vertx.sql.client;

  exports dev.horizon.cinder;

  provides io.vertx.core.spi.VertxServiceProvider with dev.horizon.cinder.TransactionLocal;
}
