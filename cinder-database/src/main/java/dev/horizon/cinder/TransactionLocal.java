package dev.horizon.cinder;

import io.vertx.core.internal.VertxBootstrap;
import io.vertx.core.spi.VertxServiceProvider;
import io.vertx.core.spi.context.storage.ContextLocal;

/**
 * The transaction of the current Vert.x context. Vert.x finds this provider before it creates an instance, which is
 * when a context local must be registered.
 */
public final class TransactionLocal implements VertxServiceProvider {

  static final ContextLocal<Transaction> KEY = ContextLocal.registerLocal(Transaction.class);

  /**
   * Gives the provider, which Vert.x finds with a service loader. Nothing else needs it.
   */
  public TransactionLocal() {
  }

  @Override
  public void init(VertxBootstrap builder) {
  }
}
