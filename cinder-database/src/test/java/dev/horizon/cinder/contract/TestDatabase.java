package dev.horizon.cinder.contract;

import dev.horizon.cinder.Driver;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

public interface TestDatabase {

  Driver driver();

  Pool pool(Vertx vertx);

  default List<String> schema() {
    try (InputStream file = getClass().getResourceAsStream("/schema.sql")) {
      if (file == null) {
        throw new IllegalStateException("The test resources of " + getClass().getName() + " have no schema.sql.");
      }
      return Arrays.stream(new String(file.readAllBytes(), StandardCharsets.UTF_8).split(";\\s*\\n"))
          .map(String::strip).filter(statement -> !statement.isEmpty()).toList();
    } catch (IOException failed) {
      throw new UncheckedIOException(failed);
    }
  }

  boolean storesEnums();

  default boolean locksRows() {
    return true;
  }

  default boolean writesIdentity() {
    return true;
  }
}
