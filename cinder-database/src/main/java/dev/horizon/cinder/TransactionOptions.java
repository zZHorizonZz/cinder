package dev.horizon.cinder;

/**
 * The isolation level and the access mode of a transaction.
 *
 * <pre>{@code
 * database.transaction(TransactionOptions.defaults().withIsolation(Isolation.SERIALIZABLE).withReadOnly(true),
 *     tx -> report());
 * }</pre>
 *
 * @param isolation the isolation level
 * @param readOnly true if the transaction only reads, which a database can run with less locking
 */
public record TransactionOptions(Isolation isolation, boolean readOnly) {

  /**
   * How much a transaction sees of the changes of the transactions that run at the same time. A database without a
   * level, such as SQLite, which has only serializable transactions, runs the next stricter one it has.
   */
  public enum Isolation {
    /** The default level of the database, such as read committed on PostgreSQL and SQL Server. */
    DEFAULT,
    /** Reads changes that other transactions have not committed. */
    READ_UNCOMMITTED,
    /** Reads only committed changes, which can change between two reads. */
    READ_COMMITTED,
    /** Reads the same rows again with the same values. */
    REPEATABLE_READ,
    /** Runs as if the transactions ran one after another. */
    SERIALIZABLE
  }

  /**
   * Gives the default options: the default isolation level of the database, and reads and writes.
   *
   * @return the options
   */
  public static TransactionOptions defaults() {
    return new TransactionOptions(Isolation.DEFAULT, false);
  }

  /**
   * Gives the options with another isolation level.
   *
   * @param isolation the isolation level
   * @return the options, with the access mode unchanged
   */
  public TransactionOptions withIsolation(Isolation isolation) {
    return new TransactionOptions(isolation, readOnly);
  }

  /**
   * Gives the options with another access mode.
   *
   * @param readOnly true if the transaction only reads
   * @return the options, with the isolation level unchanged
   */
  public TransactionOptions withReadOnly(boolean readOnly) {
    return new TransactionOptions(isolation, readOnly);
  }
}
