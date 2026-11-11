package dev.horizon.cinder.sqlite;

import dev.horizon.aquery.ParameterStyle;
import dev.horizon.basepoint.Dialect;
import dev.horizon.basepoint.sqlite.SqliteDialect;
import dev.horizon.cinder.Driver;
import dev.horizon.cinder.TransactionOptions;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * The driver of SQLite, on the JDBC client of Vert.x. SQLite keeps times as text, so the driver writes each in UTC
 * with six digits of fraction, such as {@code 2026-10-16T10:00:00.000000Z}, which sorts as the times do.
 *
 * <p>
 * SQLite has no locks of rows: a transaction that writes locks the whole database, and transactions run one after
 * another. So a transaction that may write begins with {@code BEGIN IMMEDIATE}, which takes the lock of the database at
 * once, so that two of them never wait for each other.
 * A transaction is always serializable, whatever its isolation level.
 */
public final class SqliteDriver implements Driver {

  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'")
      .withZone(ZoneOffset.UTC);
  private static final int SQLITE_CONSTRAINT = 19;

  private final Dialect dialect = new SqliteDialect(ParameterStyle.QUESTION_MARK);

  @Override
  public Dialect dialect() {
    return dialect;
  }

  @Override
  public Object bind(Object value) {
    return switch (value) {
      case Instant instant -> TIME.format(instant);
      case UUID uuid -> uuid.toString();
      case LocalDate date -> date.toString();
      case LocalTime time -> time.toString();
      case null, default -> Driver.super.bind(value);
    };
  }

  @Override
  public List<String> begin(TransactionOptions options) {
    return List.of(options.readOnly() ? "BEGIN DEFERRED" : "BEGIN IMMEDIATE");
  }

  @Override
  public boolean duplicateKey(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sql && sql.getErrorCode() == SQLITE_CONSTRAINT && sql.getMessage() != null
          && (sql.getMessage().contains("UNIQUE") || sql.getMessage().contains("PRIMARY KEY"))) {
        return true;
      }
    }
    return false;
  }
}
