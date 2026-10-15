package dev.horizon.cinder;

/**
 * The key of a value that a model source gives a field, so that an extension can find its columns without knowing
 * where the model came from. The protobuf model source gives each custom field option as the attribute of its full
 * name, such as {@code obfuscation.v1.obfuscated}.
 *
 * @param name the name of the attribute
 * @param type the type of its value
 * @param <T> the type of its value
 */
public record Attribute<T>(String name, Class<T> type) {

  /**
   * Gives an attribute.
   *
   * @param name the name of the attribute, such as {@code obfuscation.v1.obfuscated}
   * @param type the type of its value, such as {@code Boolean.class}
   * @param <T> the type of its value
   * @return the attribute
   */
  public static <T> Attribute<T> of(String name, Class<T> type) {
    return new Attribute<>(name, type);
  }
}
