package dev.horizon.cinder;

import dev.horizon.basepoint.Dialect;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.DatabaseException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What Cinder needs to know of a database and its Vert.x client beyond the SQL of its Basepoint dialect: how the
 * client binds and reads values, and how the database begins transactions and savepoints. Each database module of
 * Cinder has one, such as {@code PostgresDriver}.
 *
 * <p>
 * Values pass between the codecs and the driver as plain Java values: {@code String}, {@code Boolean},
 * {@code Integer}, {@code Long}, {@code Float}, {@code Double}, {@code BigDecimal}, {@code byte[]}, {@code Instant},
 * {@code LocalDate}, {@code LocalTime}, {@code UUID}, and the {@code JsonObject} and {@code JsonArray} of Vert.x for
 * JSON.
 */
public interface Driver {

  /** The length of a date of ISO 8601, such as {@code 2026-10-16}, before the time. */
  int DATE_LENGTH = 10;

  /** The SQLSTATE of the standard for a row whose key or unique fields another row has. */
  String UNIQUE_VIOLATION = "23505";

  /**
   * Gives the dialect of the SQL of the database, with the placeholders that its client takes.
   *
   * @return the dialect
   */
  Dialect dialect();

  /**
   * Gives the value that the client binds for a plain Java value.
   *
   * @param value the value, after the codecs of its field, or null
   * @return the value that the client binds, such as an {@code OffsetDateTime} for an {@code Instant}. The default
   * gives JSON as its text, and every other value as it is.
   */
  default Object bind(Object value) {
    return switch (value) {
      case JsonObject json -> json.encode();
      case JsonArray json -> json.encode();
      case null, default -> value;
    };
  }

  /**
   * Reads a value that the client gives for a column as a plain Java value.
   *
   * @param value the value of the column, or null
   * @param type the type that the codecs of the field take, such as {@code Instant}
   * @return the value, of the type if the driver can convert it, or as the client gave it
   * @throws ArithmeticException if a number with a fraction or out of range is read as a whole number
   */
  /**
   * Gives the null to bind to a column. A client whose untyped null the database cannot convert to every type needs
   * the null of the type of the column.
   *
   * @param type the type of the values of the column, such as {@code byte[]}
   * @return the null to bind, {@code null} unless the driver says otherwise
   */
  default Object bindNull(Class<?> type) {
    return null;
  }

  default Object read(Object value, Class<?> type) {
    if (value == null || type.isInstance(value)) {
      return value;
    }
    return switch (value) {
      case Number number when type == Boolean.class -> number.intValue() != 0;
      case Number number when type == Long.class -> exact(number).longValueExact();
      case Number number when type == Integer.class -> exact(number).intValueExact();
      case Number number when type == Short.class -> exact(number).shortValueExact();
      case Number number when type == Double.class -> number.doubleValue();
      case Number number when type == Float.class -> number.floatValue();
      case Number number when type == BigDecimal.class -> new BigDecimal(number.toString());
      case Number number when type == BigInteger.class -> new BigInteger(number.toString());
      case Buffer buffer when type == byte[].class -> buffer.getBytes();
      case OffsetDateTime time when type == Instant.class -> time.toInstant();
      case LocalDateTime time when type == Instant.class -> time.toInstant(ZoneOffset.UTC);
      case String text when type == Instant.class -> instant(text);
      case String text when type == Long.class -> Long.valueOf(text);
      case String text when type == Integer.class -> Integer.valueOf(text);
      case String text when type == Short.class -> Short.valueOf(text);
      case String text when type == Double.class -> Double.valueOf(text);
      case String text when type == Float.class -> Float.valueOf(text);
      case String text when type == UUID.class -> UUID.fromString(text);
      case String text when type == LocalDate.class -> LocalDate.parse(text);
      case String text when type == LocalTime.class -> LocalTime.parse(text);
      case String text when type == BigDecimal.class -> new BigDecimal(text);
      case String text when type == Boolean.class -> text.equals("1") || Boolean.parseBoolean(text);
      case String text when type == JsonObject.class -> new JsonObject(text);
      case String text when type == JsonArray.class -> new JsonArray(text);
      case Buffer buffer when type == JsonObject.class -> new JsonObject(buffer);
      case Buffer buffer when type == JsonArray.class -> new JsonArray(buffer);
      case UUID uuid when type == String.class -> uuid.toString();
      default -> value;
    };
  }

  /**
   * Gives a number as a decimal, so that a conversion to a whole number fails rather than drops a fraction.
   *
   * @param number the number
   * @return the decimal
   */
  private static BigDecimal exact(Number number) {
    return number instanceof BigDecimal decimal ? decimal : new BigDecimal(number.toString());
  }

  /**
   * Reads the text of a time, such as {@code 2026-10-16T10:00:00Z}, {@code 2026-10-16T10:00:00+02:00}, or a time in
   * UTC without a zone, such as {@code 2026-10-16 10:00:00.123456}.
   *
   * @param text the text
   * @return the time
   * @throws java.time.format.DateTimeParseException if the text is not a time
   */
  default Instant instant(String text) {
    String iso = text.length() > DATE_LENGTH && text.charAt(DATE_LENGTH) == ' ' ? text.substring(0, DATE_LENGTH)
        + 'T' + text.substring(DATE_LENGTH + 1) : text;
    try {
      return OffsetDateTime.parse(iso).toInstant();
    } catch (DateTimeParseException withoutZone) {
      return LocalDateTime.parse(iso).toInstant(ZoneOffset.UTC);
    }
  }

  /**
   * Reads a value of the JSON that the database gives for an include, as a plain Java value.
   *
   * @param value the value: a {@code String}, a {@code Number}, a {@code Boolean} or null
   * @param type the type that the codecs of the field take
   * @return the value, of the type if the driver can convert it. The default reads it as a column value.
   */
  default Object readJson(Object value, Class<?> type) {
    return read(value, type);
  }

  /**
   * Tells if the database gives the rows of an update with {@code RETURNING} or its equivalent. When it does not, an
   * update that returns its rows locks and reads their keys first, and reads the rows again after.
   *
   * @return true if it does. The default is true.
   */
  default boolean returnsUpdatedRows() {
    return true;
  }

  /**
   * Gives the SQL that begins a transaction. Cinder runs transactions with these statements, not with the
   * transactions of the client, so that a failed statement can roll back to a savepoint and the work goes on.
   *
   * @param options the isolation level and the access mode
   * @return the statements, in order. The default is {@code START TRANSACTION} with the options, as the standard
   * writes it.
   */
  default List<String> begin(TransactionOptions options) {
    return List.of(settings(options).map(settings -> "START TRANSACTION " + settings).orElse("START TRANSACTION"));
  }

  /**
   * Gives the SQL that commits a transaction.
   *
   * @return the SQL. The default is {@code COMMIT}.
   */
  default String commit() {
    return "COMMIT";
  }

  /**
   * Gives the SQL that rolls a transaction back.
   *
   * @return the SQL. The default is {@code ROLLBACK}.
   */
  default String rollback() {
    return "ROLLBACK";
  }

  /**
   * Gives the SQL that gives the connection of a transaction back to its state before the transaction, after the
   * commit or the rollback, such as for a database whose isolation level is a setting of the connection.
   *
   * @param options the options of the transaction
   * @return the statements, in order. The default is none.
   */
  default List<String> end(TransactionOptions options) {
    return List.of();
  }

  /**
   * Gives the settings of {@code SET TRANSACTION} for the options of a transaction.
   *
   * @param options the options
   * @return the settings, such as {@code ISOLATION LEVEL SERIALIZABLE, READ ONLY}, or empty for the defaults
   */
  default Optional<String> settings(TransactionOptions options) {
    String isolation = switch (options.isolation()) {
      case DEFAULT -> "";
      case READ_UNCOMMITTED -> "ISOLATION LEVEL READ UNCOMMITTED";
      case READ_COMMITTED -> "ISOLATION LEVEL READ COMMITTED";
      case REPEATABLE_READ -> "ISOLATION LEVEL REPEATABLE READ";
      case SERIALIZABLE -> "ISOLATION LEVEL SERIALIZABLE";
    };
    String access = options.readOnly() ? "READ ONLY" : "";
    String settings = isolation.isEmpty() || access.isEmpty() ? isolation + access : isolation + ", " + access;
    return settings.isEmpty() ? Optional.empty() : Optional.of(settings);
  }

  /**
   * Tells if a failure of a statement is a row whose key or unique fields another row has.
   *
   * @param failure the failure
   * @return true if it is. The default reads the SQLSTATE {@code 23505} of the standard.
   */
  default boolean duplicateKey(Throwable failure) {
    return failure instanceof DatabaseException database && UNIQUE_VIOLATION.equals(database.getSqlState());
  }

  /**
   * Gives the SQL that starts a savepoint.
   *
   * @param name the name of the savepoint, such as {@code cinder_1}
   * @return the SQL. The default is {@code SAVEPOINT <name>}.
   */
  default String savepoint(String name) {
    return "SAVEPOINT " + name;
  }

  /**
   * Gives the SQL that rolls a transaction back to a savepoint.
   *
   * @param name the name of the savepoint
   * @return the SQL. The default is {@code ROLLBACK TO SAVEPOINT <name>}.
   */
  default String rollbackTo(String name) {
    return "ROLLBACK TO SAVEPOINT " + name;
  }

  /**
   * Gives the SQL that releases a savepoint whose work succeeded.
   *
   * @param name the name of the savepoint
   * @return the SQL, or empty if the database has no release. The default is {@code RELEASE SAVEPOINT <name>}.
   */
  default Optional<String> release(String name) {
    return Optional.of("RELEASE SAVEPOINT " + name);
  }
}
