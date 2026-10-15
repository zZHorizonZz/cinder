package dev.horizon.cinder.impl;

import dev.horizon.cinder.Execution;
import io.vertx.core.Handler;
import io.vertx.core.streams.ReadStream;
import io.vertx.sqlclient.Row;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The entities of the rows of a stream. It reads the rows in batches, gives each batch to the parts of the operation,
 * and emits the entities as the demand of its handler allows. The rows pause while a batch completes, and while the
 * handler has entities to take.
 */
final class EntityStream<E> implements ReadStream<E> {

  private final ReadStream<Row> rows;
  private final Execution<E> execution;
  private final int fetch;
  private final Deque<E> pending = new ArrayDeque<>();
  private List<E> batch = new ArrayList<>();
  private Handler<E> handler;
  private Handler<Void> endHandler;
  private Handler<Throwable> exceptionHandler;
  private long demand = Long.MAX_VALUE;
  private boolean completing;
  private boolean ended;
  private boolean finished;
  private boolean rowsPaused;

  EntityStream(ReadStream<Row> rows, Execution<E> execution, int fetch) {
    this.rows = rows;
    this.execution = execution;
    this.fetch = fetch;
  }

  @Override
  public ReadStream<E> exceptionHandler(Handler<Throwable> handler) {
    this.exceptionHandler = handler;
    return this;
  }

  @Override
  public ReadStream<E> handler(Handler<E> handler) {
    this.handler = handler;
    if (handler == null) {
      rows.handler(null);
      return this;
    }
    rows.exceptionHandler(this::fail);
    rows.endHandler(ignored -> {
      ended = true;
      flush();
    });
    rows.handler(this::row);
    return this;
  }

  @Override
  public ReadStream<E> pause() {
    demand = 0;
    return this;
  }

  @Override
  public ReadStream<E> resume() {
    demand = Long.MAX_VALUE;
    drain();
    return this;
  }

  @Override
  public ReadStream<E> fetch(long amount) {
    demand = Long.MAX_VALUE - demand < amount ? Long.MAX_VALUE : demand + amount;
    drain();
    return this;
  }

  @Override
  public ReadStream<E> endHandler(Handler<Void> handler) {
    this.endHandler = handler;
    return this;
  }

  private void row(Row row) {
    try {
      batch.add(execution.read(row));
    } catch (RuntimeException failed) {
      fail(failed);
      return;
    }
    if (batch.size() >= fetch) {
      flush();
    }
  }

  private void flush() {
    if (completing) {
      return;
    }
    if (batch.isEmpty()) {
      drain();
      return;
    }
    List<E> done = batch;
    batch = new ArrayList<>();
    completing = true;
    pauseRows();
    execution.complete(done).onComplete(completed -> {
      completing = false;
      if (completed.failed()) {
        fail(completed.cause());
        return;
      }
      pending.addAll(completed.result());
      drain();
    });
  }

  private void drain() {
    while (demand > 0 && !pending.isEmpty() && !finished) {
      if (demand != Long.MAX_VALUE) {
        demand--;
      }
      handler.handle(pending.poll());
    }
    if (finished || completing) {
      return;
    }
    if (pending.isEmpty() && ended) {
      if (!batch.isEmpty()) {
        flush();
        return;
      }
      finished = true;
      if (endHandler != null) {
        endHandler.handle(null);
      }
    } else if (pending.isEmpty() && demand > 0) {
      resumeRows();
    }
  }

  private void pauseRows() {
    if (!rowsPaused) {
      rowsPaused = true;
      rows.pause();
    }
  }

  private void resumeRows() {
    if (rowsPaused && !ended) {
      rowsPaused = false;
      rows.resume();
    }
  }

  private void fail(Throwable failure) {
    if (finished) {
      return;
    }
    finished = true;
    if (exceptionHandler != null) {
      exceptionHandler.handle(failure);
    }
  }
}
