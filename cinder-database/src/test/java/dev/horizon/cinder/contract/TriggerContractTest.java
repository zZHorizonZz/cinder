package dev.horizon.cinder.contract;

import static dev.horizon.basepoint.filter.Filter.eq;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.horizon.cinder.Conflict;
import dev.horizon.cinder.Table;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public abstract class TriggerContractTest extends ContractTest {

  private Table<Shop.Note> notes;

  @BeforeEach
  void notes() {
    notes = database.table(Shop.NOTE);
  }

  @Test
  @DisplayName("Gives an inserted row with the values that a trigger wrote")
  void insert() throws TimeoutException {
    Shop.Note note = await(notes.insert(new Shop.Note(null, "Hello", null)));

    assertThat(note.id()).isNotNull();
    assertThat(note.slug()).isEqualTo("hello");
  }

  @Test
  @DisplayName("Gives inserted rows in their order, with the keys that the database gave them")
  void insertMany() throws TimeoutException {
    List<Shop.Note> inserted = await(notes.insert(List.of(new Shop.Note(null, "One", null), new Shop.Note(null,
        "Two", null), new Shop.Note(null, "Three", null))));

    assertThat(inserted).extracting(Shop.Note::slug).containsExactly("one", "two", "three");
    assertThat(inserted).extracting(Shop.Note::id).doesNotContainNull().doesNotHaveDuplicates();
  }

  @Test
  @DisplayName("Gives an updated row with the values that a trigger wrote")
  void update() throws TimeoutException {
    Shop.Note note = await(notes.insert(new Shop.Note(null, "Hello", null)));

    List<Shop.Note> updated = await(notes.returning(notes.update().set("body", "World").where(eq("id",
        note.id()))));

    assertThat(updated).containsExactly(new Shop.Note(note.id(), "World", "world"));
    assertThat(await(notes.update(new Shop.Note(note.id(), "Again", null), "body"))).map(Shop.Note::slug)
        .contains("again");
  }

  @Test
  @DisplayName("Gives a deleted row as it was stored")
  void delete() throws TimeoutException {
    Shop.Note note = await(notes.insert(new Shop.Note(null, "Hello", null)));

    assertThat(await(notes.returning(notes.delete().where(eq("id", note.id()))))).containsExactly(note);
    assertThat(await(notes.find(note.id()))).isEmpty();
  }

  @Test
  @DisplayName("Gives the rows of an insert with a conflict as the table holds them, kept ones too")
  void conflict() throws TimeoutException {
    assumeTrue(testDatabase().writesIdentity());
    Shop.Note note = await(notes.insert(new Shop.Note(null, "Hello", null)));

    List<Shop.Note> kept = await(notes.insert(List.of(new Shop.Note(note.id(), "Other", null)),
        Conflict.ignore()));
    List<Shop.Note> merged = await(notes.insert(List.of(new Shop.Note(note.id(), "Merged", null)),
        Conflict.merge()));

    assertThat(kept).containsExactly(note);
    assertThat(merged).containsExactly(new Shop.Note(note.id(), "Merged", "merged"));
  }
}
