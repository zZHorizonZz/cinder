package dev.horizon.cinder;

import dev.horizon.basepoint.Expression;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The changes of a row, by fields of the model: the changes of an update, or the values of an insert.
 *
 * <pre>{@code
 * Assignments.none().set("name", "records").increment("version", 1).unset("deleted_at")
 * }</pre>
 *
 * @param changes the changes, in order. A later change of a field replaces an earlier one.
 */
public record Assignments(List<Change> changes) {

  /**
   * A change of one field.
   */
  public sealed interface Change {

    /**
     * Gives the field of the change.
     *
     * @return the name of the field, such as {@code version}
     */
    String field();
  }

  /**
   * Sets a field to a value.
   *
   * @param field the name of the field
   * @param value the value, which goes through the codecs of the field
   */
  public record SetValue(String field, Object value) implements Change {
  }

  /**
   * Sets a field to a value that the database computes as the statement runs. The write reads it back with
   * {@code RETURNING}.
   *
   * @param field the name of the field
   * @param expression the SQL of the value, such as {@code CURRENT_TIMESTAMP}
   */
  public record Compute(String field, Expression expression) implements Change {
  }

  /**
   * Adds an amount to a number field.
   *
   * @param field the name of the field
   * @param amount the amount, negative to subtract
   */
  public record Increment(String field, Number amount) implements Change {
  }

  /**
   * Sets a field to NULL, or to its default when the column cannot be NULL.
   *
   * @param field the name of the field
   */
  public record Unset(String field) implements Change {
  }

  public Assignments {
    changes = List.copyOf(changes);
  }

  /**
   * Gives assignments without changes, to add the changes to.
   *
   * @return the assignments
   */
  public static Assignments none() {
    return new Assignments(List.of());
  }

  /**
   * Adds a change that sets a field to a value.
   *
   * @param field the name of the field, such as {@code state}
   * @param value the value
   * @return the assignments, with the change after the others
   */
  public Assignments set(String field, Object value) {
    return with(new SetValue(field, value));
  }

  /**
   * Adds a change that sets a field to a value that the database computes.
   *
   * @param field the name of the field, such as {@code update_time}
   * @param expression the SQL of the value, such as {@code CURRENT_TIMESTAMP} or {@code version + 1}, which the
   * dialect and the decorators render as any node
   * @return the assignments, with the change after the others
   */
  public Assignments set(String field, Expression expression) {
    return with(new Compute(field, expression));
  }

  /**
   * Adds a change that adds an amount to a number field.
   *
   * @param field the name of the field, such as {@code version}
   * @param amount the amount, negative to subtract
   * @return the assignments, with the change after the others
   */
  public Assignments increment(String field, Number amount) {
    return with(new Increment(field, amount));
  }

  /**
   * Adds a change that sets a field to NULL, or to its default when the column cannot be NULL.
   *
   * @param field the name of the field, such as {@code cancel_time}
   * @return the assignments, with the change after the others
   */
  public Assignments unset(String field) {
    return with(new Unset(field));
  }

  /**
   * Leaves a field out of the row, so that the statement does not write its column, and an insert leaves it to the
   * column default.
   *
   * @param field the name of the field
   * @return the assignments, without the changes of the field
   */
  public Assignments without(String field) {
    return new Assignments(changes.stream().filter(change -> !change.field().equals(field)).toList());
  }

  /**
   * Gives the change of a field.
   *
   * @param field the name of the field
   * @return the last change of the field, or empty if the row does not change it
   */
  public Optional<Change> change(String field) {
    return changes.reversed().stream().filter(change -> change.field().equals(field)).findFirst();
  }

  /**
   * Gives the value that the row sets a field to.
   *
   * @param field the name of the field
   * @return the value of the last change of the field if it sets a value, or empty if it is another change, the value
   * is null or the row does not change the field
   */
  public Optional<Object> value(String field) {
    return change(field).filter(SetValue.class::isInstance).map(change -> ((SetValue) change).value());
  }

  private Assignments with(Change change) {
    List<Change> more = new ArrayList<>(changes);
    more.add(change);
    return new Assignments(more);
  }
}
