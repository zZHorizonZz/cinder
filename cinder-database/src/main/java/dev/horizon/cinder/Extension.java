package dev.horizon.cinder;

import dev.horizon.basepoint.Decorator;
import java.util.List;

/**
 * Adds behavior to every operation of a database, through five points:
 *
 * <ul>
 * <li>a {@link ModelSource} maps types of entities to tables, as the protobuf extension does for messages.</li>
 * <li>a {@link Part} of every operation of a model changes its rows, its statement and its entities, as tenancy, soft
 * deletes, keys, times and versions do.</li>
 * <li>a Basepoint {@link Decorator} rewrites every node before its SQL, as a clause in the syntax of a dialect
 * does.</li>
 * <li>a {@link TypeCodec} changes the value of a field on its way to the database and back, as encryption does.</li>
 * <li>an {@link Interceptor} wraps the run of each statement, as metrics and retries do.</li>
 * </ul>
 *
 * Each point defaults to nothing, so an extension overrides only the points it uses. The database applies the
 * extensions in the order it got them. New operations need no extension: they are a {@link Part} or a
 * {@link Terminal} of the operations that use them.
 */
public interface Extension {

  /**
   * Gives the model sources of the extension.
   *
   * @return the sources, or none
   */
  default List<ModelSource> models() {
    return List.of();
  }

  /**
   * Gives the parts of every operation of a model. The database asks once per model. The query of an include is an
   * operation of the related model, so it has the parts of that model.
   *
   * @param model the model, to know its fields
   * @return the parts, or none to leave the operations of the model as they are
   */
  default List<Part> parts(Model<?> model) {
    return List.of();
  }

  /**
   * Gives the decorator of every node of every statement, such as one that writes a clause in the syntax of a
   * dialect. The decorators of the extensions run before those of the dialect.
   *
   * @return the decorator, or {@link Decorator#none()} to leave the nodes as they are
   */
  default Decorator decorator() {
    return Decorator.none();
  }

  /**
   * Gives the codecs of the extension.
   *
   * @return the codecs, or none
   */
  default List<TypeCodec> codecs() {
    return List.of();
  }

  /**
   * Gives the interceptors of the extension.
   *
   * @return the interceptors, or none
   */
  default List<Interceptor> interceptors() {
    return List.of();
  }
}
