package dev.horizon.cinder.impl;

import dev.horizon.cinder.Database;
import dev.horizon.cinder.Driver;
import dev.horizon.cinder.Extension;
import io.vertx.sqlclient.Pool;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

public final class DatabaseBuilder implements Database.Builder {

  private static final int PAGE_TOKEN_KEY_BITS = 256;

  private final Pool pool;
  private final Driver driver;
  private final List<Extension> extensions = new ArrayList<>();
  private Clock clock = Clock.systemUTC();
  private SecretKey pageTokenKey;
  private int maxPageSize = Database.MAX_PAGE_SIZE;

  public DatabaseBuilder(Pool pool, Driver driver) {
    this.pool = Objects.requireNonNull(pool, "pool");
    this.driver = Objects.requireNonNull(driver, "driver");
  }

  @Override
  public Database.Builder extension(Extension extension) {
    extensions.add(Objects.requireNonNull(extension, "extension"));
    return this;
  }

  @Override
  public Database.Builder clock(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock");
    return this;
  }

  @Override
  public Database.Builder pageTokenKey(SecretKey key) {
    this.pageTokenKey = Objects.requireNonNull(key, "key");
    return this;
  }

  @Override
  public Database.Builder maxPageSize(int rows) {
    if (rows < 1) {
      throw new IllegalArgumentException("A page holds at least one row, not " + rows + ".");
    }
    this.maxPageSize = rows;
    return this;
  }

  @Override
  public Database build() {
    return new CinderDatabase(pool, driver, extensions, clock, pageTokenKey == null ? randomKey() : pageTokenKey,
        maxPageSize);
  }

  private static SecretKey randomKey() {
    try {
      KeyGenerator generator = KeyGenerator.getInstance("AES");
      generator.init(PAGE_TOKEN_KEY_BITS);
      return generator.generateKey();
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("Every Java platform has AES.", impossible);
    }
  }
}
