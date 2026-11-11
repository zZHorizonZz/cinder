package dev.horizon.cinder.mariadb;

import dev.horizon.aquery.ParameterStyle;
import dev.horizon.basepoint.Dialect;
import dev.horizon.basepoint.mariadb.MariaDbDialect;
import dev.horizon.cinder.Driver;
import dev.horizon.cinder.TransactionOptions;
import io.vertx.core.buffer.Buffer;
import io.vertx.sqlclient.DatabaseException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * The driver of MariaDB, on the MySQL client of Vert.x. It binds times as {@code DATETIME} values in UTC.
 *
 * <p>
 * MariaDB gives the rows of an insert and a delete with {@code RETURNING}, but not those of an update. So an update
 * that returns its rows locks and reads their keys first, and reads the rows again after.
 */
public final class MariaDbDriver implements Driver {

  private static final int ER_DUP_ENTRY = 1062;
  private static final int ER_DUP_ENTRY_WITH_KEY_NAME = 1586;

  private final Dialect dialect = new MariaDbDialect(ParameterStyle.QUESTION_MARK);

  @Override
  public Dialect dialect() {
    return dialect;
  }

  @Override
  public Object bind(Object value) {
    return switch (value) {
      case Instant instant -> LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
      case byte[] bytes -> Buffer.buffer(bytes);
      case UUID uuid -> uuid.toString();
      case null, default -> Driver.super.bind(value);
    };
  }

  @Override
  public boolean returnsUpdatedRows() {
    return false;
  }

  @Override
  public List<String> begin(TransactionOptions options) {
    return settings(options).map(settings -> List.of("SET TRANSACTION " + settings, "START TRANSACTION"))
        .orElseGet(() -> List.of("START TRANSACTION"));
  }

  @Override
  public boolean duplicateKey(Throwable failure) {
    return failure instanceof DatabaseException database && (database.getErrorCode() == ER_DUP_ENTRY
        || database.getErrorCode() == ER_DUP_ENTRY_WITH_KEY_NAME);
  }
}
