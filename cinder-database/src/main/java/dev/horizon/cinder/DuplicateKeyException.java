package dev.horizon.cinder;

/**
 * An insert of a row whose key or unique fields another row has, without a {@link Conflict} policy.
 */
public final class DuplicateKeyException extends RuntimeException {

  private final Model<?> model;

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
