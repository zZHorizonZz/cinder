package dev.horizon.cinder.sqlserver;

import dev.horizon.aquery.ParameterStyle;
import dev.horizon.basepoint.Dialect;
import dev.horizon.basepoint.sqlserver.SqlServerDialect;
import dev.horizon.cinder.Driver;
import dev.horizon.cinder.TransactionOptions;
import io.vertx.core.buffer.Buffer;
import io.vertx.sqlclient.DatabaseException;
import io.vertx.sqlclient.data.NullValue;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * The driver of SQL Server, on the SQL Server client of Vert.x. It binds times as {@code DATETIME2} values in UTC, and
 * a null of a binary column as a binary null, because SQL Server does not convert the untyped null of the client to
 * {@code VARBINARY}.
 *
 * <p>
 * The isolation level of SQL Server is a setting of the connection, so a transaction with a level sets it before it
 * begins, and sets {@code READ COMMITTED} again after it ends. SQL Server has no read-only transactions, so a
 * read-only transaction reads and writes.
 */
public final class SqlServerDriver implements Driver {

  private static final int DUPLICATE_KEY_ROW = 2601;
  private static final int UNIQUE_KEY_VIOLATION = 2627;
  private static final String DEFAULT_ISOLATION = "SET TRANSACTION ISOLATION LEVEL READ COMMITTED";

  private final Dialect dialect = new SqlServerDialect(ParameterStyle.AT_P);

  @Override
  public Dialect dialect() {
    return dialect;
  }

  @Override
  public Object bind(Object value) {
    return switch (value) {
      case Instant instant -> LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
      case byte[] bytes -> Buffer.buffer(bytes);
      case null, default -> Driver.super.bind(value);
    };
  }

  @Override
  public Object bindNull(Class<?> type) {
    return type == byte[].class ? NullValue.Buffer : null;
  }

  @Override
  public Object readJson(Object value, Class<?> type) {
    if (type == byte[].class && value instanceof String text) {
      return Base64.getDecoder().decode(text);
    }
    return read(value, type);
  }

  @Override
  public List<String> begin(TransactionOptions options) {
    List<String> begin = new ArrayList<>();
    isolation(options).ifPresent(begin::add);
    begin.add("BEGIN TRANSACTION");
    return begin;
  }

  @Override
  public String commit() {
    return "COMMIT TRANSACTION";
  }

  @Override
  public String rollback() {
    return "ROLLBACK TRANSACTION";
  }

  @Override
  public List<String> end(TransactionOptions options) {
    return isolation(options).isPresent() ? List.of(DEFAULT_ISOLATION) : List.of();
  }

  @Override
  public String savepoint(String name) {
    return "SAVE TRANSACTION " + name;
  }

  @Override
  public String rollbackTo(String name) {
    return "ROLLBACK TRANSACTION " + name;
  }

  @Override
  public Optional<String> release(String name) {
    return Optional.empty();
  }

  @Override
  public boolean duplicateKey(Throwable failure) {
    return failure instanceof DatabaseException database && (database.getErrorCode() == DUPLICATE_KEY_ROW
        || database.getErrorCode() == UNIQUE_KEY_VIOLATION);
  }

  /** Gives the setting of the isolation level of a transaction, which SQL Server keeps for the connection. */
  private Optional<String> isolation(TransactionOptions options) {
    return settings(options.withReadOnly(false)).map(settings -> "SET TRANSACTION " + settings);
  }
}
