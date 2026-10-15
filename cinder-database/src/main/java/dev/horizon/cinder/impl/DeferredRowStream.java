package dev.horizon.cinder.impl;

import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.streams.ReadStream;
import io.vertx.sqlclient.PreparedStatement;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowStream;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The rows of a cursor that opens when the stream gets its handler. The stream keeps the demand that it gets before
 * the cursor opens, and gives the cursor back when the rows end or fail.
 */
final class DeferredRowStream implements ReadStream<Row> {

  /**
   * The cursor of a statement.
   *
   * @param prepared the prepared statement, which the stream closes after the cursor
   * @param rows the rows of the cursor
   */
  record Cursor(PreparedStatement prepared, RowStream<Row> rows) {
  }

  /**
   * An open cursor.
   *
   * @param cursor the cursor
   * @param finish ends what the cursor holds, such as its transaction: with null after the last row, or with the
   * failure
   */
  record Source(Cursor cursor, Function<Throwable, Future<Void>> finish) {

    RowStream<Row> rows() {
      return cursor.rows();
    }

    Future<Void> close(Throwable failure) {
      return cursor.rows().close().transform(closed -> cursor.prepared().close()).transform(closed -> finish
          .apply(failure));
    }
  }

  private final Supplier<Future<Source>> opener;
  private Handler<Row> handler;
  private Handler<Void> endHandler;
  private Handler<Throwable> exceptionHandler;
  private Source source;
  private boolean opening;
  private boolean paused;
  private long fetched;

  DeferredRowStream(Supplier<Future<Source>> opener) {
    this.opener = opener;
  }

  @Override
  public synchronized ReadStream<Row> exceptionHandler(Handler<Throwable> handler) {
    this.exceptionHandler = handler;
    return this;
  }

  @Override
  public ReadStream<Row> handler(Handler<Row> handler) {
    Source open;
    synchronized (this) {
      this.handler = handler;
      open = source;
      if (open == null && handler != null && !opening) {
        opening = true;
        opener.get().onComplete(opened -> {
          if (opened.succeeded()) {
            attach(opened.result());
          } else {
            fail(opened.cause());
          }
        });
      }
    }
    if (open != null) {
      open.rows().handler(handler);
    }
    return this;
  }

  @Override
  public ReadStream<Row> pause() {
    Source open;
    synchronized (this) {
      paused = true;
      fetched = 0;
      open = source;
    }
    if (open != null) {
      open.rows().pause();
    }
    return this;
  }

  @Override
  public ReadStream<Row> resume() {
    Source open;
    synchronized (this) {
      paused = false;
      open = source;
    }
    if (open != null) {
      open.rows().resume();
    }
    return this;
  }

  @Override
  public ReadStream<Row> fetch(long amount) {
    Source open;
    synchronized (this) {
      fetched = Long.MAX_VALUE - fetched < amount ? Long.MAX_VALUE : fetched + amount;
      open = source;
    }
    if (open != null) {
      open.rows().fetch(amount);
    }
    return this;
  }

  @Override
  public synchronized ReadStream<Row> endHandler(Handler<Void> handler) {
    this.endHandler = handler;
    return this;
  }

  private void attach(Source opened) {
    Handler<Row> rows;
    boolean pause;
    long demand;
    synchronized (this) {
      source = opened;
      rows = handler;
      pause = paused;
      demand = fetched;
    }
    RowStream<Row> cursor = opened.rows();
    cursor.exceptionHandler(failure -> opened.close(failure).onComplete(finished -> fail(failure)));
    cursor.endHandler(ignored -> opened.close(null)
        .onComplete(finished -> {
          if (finished.failed()) {
            fail(finished.cause());
          } else {
            end();
          }
        }));
    if (pause) {
      cursor.pause();
      if (demand > 0) {
        cursor.fetch(demand);
      }
    }
    cursor.handler(rows);
  }

  private void end() {
    Handler<Void> handler;
    synchronized (this) {
      handler = endHandler;
    }
    if (handler != null) {
      handler.handle(null);
    }
  }

  private void fail(Throwable failure) {
    Handler<Throwable> handler;
    synchronized (this) {
      handler = exceptionHandler;
    }
    if (handler != null) {
      handler.handle(failure);
    }
  }
}
