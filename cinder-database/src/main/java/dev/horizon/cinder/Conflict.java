package dev.horizon.cinder;

import java.util.List;

/**
 * What an insert does with a row whose key, or whose unique fields, another row has. The database decides the
 * conflict in the statement, as {@code ON CONFLICT} or {@code MERGE} does.
 */
public sealed interface Conflict {

  /**
   * Gives the fields whose values conflict.
   *
   * @return the fields of a unique index, or none for the primary key
   */
  List<String> target();

  /**
   * Gives the policy for the conflicts of other fields.
   *
   * @param fields the fields of a unique index, such as {@code email}
   * @return the policy, with the fields as its target
   */
  Conflict on(String... fields);

  /**
   * Leaves out each row that conflicts, and keeps the row that the table has.
   *
   * @return the policy, on the primary key
   */
  static Conflict ignore() {
    return new Ignore(List.of());
  }

  /**
   * Updates the row that the table has with the values of each row that conflicts, as an upsert does.
   *
   * @param fields the fields to update, or none for every field but those of the target
   * @return the policy, on the primary key
   */
  static Conflict merge(String... fields) {
    return new Merge(List.of(), List.of(fields));
  }

  /**
   * Leaves out each row that conflicts.
   *
   * @param target the fields whose values conflict, or none for the primary key
   */
  record Ignore(List<String> target) implements Conflict {

    public Ignore {
      target = List.copyOf(target);
    }

    @Override
    public Conflict on(String... fields) {
      return new Ignore(List.of(fields));
    }
  }

  /**
   * Updates the row that the table has with the values of each row that conflicts.
   *
   * @param target the fields whose values conflict, or none for the primary key
   * @param fields the fields to update, or none for every field but those of the target
   */
  record Merge(List<String> target, List<String> fields) implements Conflict {

    public Merge {
      target = List.copyOf(target);
      fields = List.copyOf(fields);
    }

    @Override
    public Conflict on(String... fields) {
      return new Merge(List.of(fields), this.fields);
    }
  }
}
