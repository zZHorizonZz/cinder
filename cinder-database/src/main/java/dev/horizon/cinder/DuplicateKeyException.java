package dev.horizon.cinder;

/**
 * An insert of a row whose key or unique fields another row has, without a {@link Conflict} policy.
 */
public final class DuplicateKeyException extends RuntimeException {

  /** The model of the table whose key the row repeats. */
  private final transient Model<?> model;

  /**
   * Gives the failure of an insert.
   *
   * @param model the model of the table whose key the row repeats
   * @param message the message of the database
   * @param cause the failure of the statement
   */
  public DuplicateKeyException(Model<?> model, String message, Throwable cause) {
    super(message, cause);
    this.model = model;
  }

  /**
   * Gives the model of the row.
   *
   * @return the model of the table whose key the row repeats
   */
  public Model<?> model() {
    return model;
  }
}
