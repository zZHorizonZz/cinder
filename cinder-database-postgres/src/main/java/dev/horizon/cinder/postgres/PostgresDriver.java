package dev.horizon.cinder.postgres;

import dev.horizon.aquery.ParameterStyle;
import dev.horizon.basepoint.Dialect;
import dev.horizon.basepoint.postgres.PostgresDialect;
import dev.horizon.cinder.Driver;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;

/**
 * The driver of PostgreSQL, on the PostgreSQL client of Vert.x. It binds times as {@code timestamptz} values in UTC,
 * and JSON as the JSON values of the client.
 */
public final class PostgresDriver implements Driver {

  private static final String HEX = "\\x";

  private final Dialect dialect = new PostgresDialect(ParameterStyle.DOLLAR);

  @Override
  public Dialect dialect() {
    return dialect;
  }

  @Override
  public Object bind(Object value) {
    return switch (value) {
      case Instant instant -> instant.atOffset(ZoneOffset.UTC);
      case byte[] bytes -> Buffer.buffer(bytes);
      case JsonObject json -> json;
      case JsonArray json -> json;
      case null, default -> value;
    };
  }

  @Override
  public Object readJson(Object value, Class<?> type) {
    if (type == byte[].class && value instanceof String text && text.startsWith(HEX)) {
      return HexFormat.of().parseHex(text.substring(HEX.length()));
    }
    return read(value, type);
  }

}
