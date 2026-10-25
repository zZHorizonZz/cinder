package dev.horizon.cinder;

import java.util.Optional;

/**
 * Gives the models of the entities it knows, such as protobuf messages with a table option. A database asks the
 * sources of its extensions in order, and takes the first model it gets.
 *
 * <p>
 * A source gets an entity, not a class, so it reads what it needs from the entity itself and never reflects on the
 * caller's classes, which a module that does not open its packages refuses.
 */
public interface ModelSource {

  /**
   * Gives the model of an entity's type.
   *
   * @param prototype an entity of the type, such as {@code Order.getDefaultInstance()}
   * @param <E> the type of the entities
   * @return the model, or empty if the source does not know the type
   * @throws IllegalArgumentException if the source knows the type but cannot map it, such as a table without a key
   */
  <E> Optional<Model<E>> model(E prototype);
}
