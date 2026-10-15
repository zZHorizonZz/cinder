package dev.horizon.cinder;

import java.util.Optional;

/**
 * Gives the models of the types it knows, such as protobuf messages with a table option. A database asks the sources
 * of its extensions in order, and takes the first model it gets.
 */
public interface ModelSource {

  /**
   * Gives the model of a type.
   *
   * @param type the type of the entities
   * @param <E> the type of the entities
   * @return the model, or empty if the source does not know the type
   * @throws IllegalArgumentException if the source knows the type but cannot map it, such as a table without a key
   */
  <E> Optional<Model<E>> model(Class<E> type);
}
