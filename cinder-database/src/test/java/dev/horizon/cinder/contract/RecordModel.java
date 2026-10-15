package dev.horizon.cinder.contract;

import dev.horizon.cinder.Attribute;
import dev.horizon.cinder.Field;
import dev.horizon.cinder.Model;
import dev.horizon.cinder.Relation;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class RecordModel<E extends Record> implements Model<E> {

  private record RecordField<E>(String name, String column, Class<Object> type, boolean key, boolean nullable,
      RecordComponent component, Map<String, Object> attributes) implements Field<E, Object> {

    @Override
    public Object get(E entity) {
      try {
        return component.getAccessor().invoke(entity);
      } catch (IllegalAccessException | InvocationTargetException failed) {
        throw new IllegalStateException(failed);
      }
    }

    @Override
    public <T> Optional<T> attribute(Attribute<T> attribute) {
      return Optional.ofNullable(attributes.get(attribute.name())).map(attribute.type()::cast);
    }
  }

  private final Class<E> type;
  private final String table;
  private final List<String> key;
  private final Map<String, String> columns;
  private final Set<String> nullable;
  private final List<Relation<?>> relations;
  private final Map<String, Map<String, Object>> attributes;
  private final List<Field<E, ?>> fields;

  private RecordModel(Class<E> type, String table, List<String> key, Map<String, String> columns,
      Set<String> nullable, List<Relation<?>> relations, Map<String, Map<String, Object>> attributes) {
    this.type = type;
    this.table = table;
    this.key = key;
    this.columns = columns;
    this.nullable = nullable;
    this.relations = relations;
    this.attributes = attributes;
    List<Field<E, ?>> all = new ArrayList<>();
    for (RecordComponent component : type.getRecordComponents()) {
      String name = snake(component.getName());
      if (relations.stream().noneMatch(relation -> relation.name().equals(name))) {
        @SuppressWarnings("unchecked")
        Class<Object> componentType = (Class<Object>) component.getType();
        all.add(new RecordField<>(name, columns.getOrDefault(name, name), componentType, key.contains(name),
            nullable.contains(name), component, attributes.getOrDefault(name, Map.of())));
      }
    }
    this.fields = List.copyOf(all);
  }

  static <E extends Record> RecordModel<E> of(Class<E> type, String table, String... key) {
    return new RecordModel<>(type, table, List.of(key), Map.of(), Set.of(), List.of(), Map.of());
  }

  RecordModel<E> column(String field, String column) {
    Map<String, String> more = new HashMap<>(columns);
    more.put(field, column);
    return new RecordModel<>(type, table, key, more, nullable, relations, attributes);
  }

  RecordModel<E> nullable(String... fields) {
    return new RecordModel<>(type, table, key, columns, Set.of(fields), relations, attributes);
  }

  RecordModel<E> relation(Relation<?> relation) {
    List<Relation<?>> more = new ArrayList<>(relations);
    more.add(relation);
    return new RecordModel<>(type, table, key, columns, nullable, more, attributes);
  }

  <T> RecordModel<E> attribute(String field, Attribute<T> attribute, T value) {
    Map<String, Map<String, Object>> more = new HashMap<>(attributes);
    Map<String, Object> ofField = new HashMap<>(more.getOrDefault(field, Map.of()));
    ofField.put(attribute.name(), value);
    more.put(field, ofField);
    return new RecordModel<>(type, table, key, columns, nullable, relations, more);
  }

  @Override
  public Class<E> type() {
    return type;
  }

  @Override
  public String table() {
    return table;
  }

  @Override
  public List<Field<E, ?>> fields() {
    return fields;
  }

  @Override
  public List<Field<E, ?>> key() {
    return fields.stream().filter(Field::key).toList();
  }

  @Override
  public Optional<Field<E, ?>> field(String name) {
    return fields.stream().filter(field -> field.name().equals(name) || field.column().equals(name)).findFirst();
  }

  @Override
  public List<Relation<?>> relations() {
    return relations;
  }

  @Override
  public Optional<Relation<?>> relation(String name) {
    return relations.stream().filter(relation -> relation.name().equals(name)).findFirst();
  }

  @Override
  public E assemble(Map<String, Object> values) {
    RecordComponent[] components = type.getRecordComponents();
    Object[] arguments = new Object[components.length];
    for (int i = 0; i < components.length; i++) {
      String name = snake(components[i].getName());
      arguments[i] = values.containsKey(name) ? values.get(name)
          : components[i].getType() == List.class ? List.of() : null;
    }
    return construct(arguments);
  }

  @Override
  public E with(E entity, String name, Object value) {
    RecordComponent[] components = type.getRecordComponents();
    Object[] arguments = new Object[components.length];
    boolean found = false;
    for (int i = 0; i < components.length; i++) {
      try {
        arguments[i] = components[i].getAccessor().invoke(entity);
      } catch (IllegalAccessException | InvocationTargetException failed) {
        throw new IllegalStateException(failed);
      }
      if (snake(components[i].getName()).equals(name)) {
        arguments[i] = value;
        found = true;
      }
    }
    if (!found) {
      throw new IllegalArgumentException("The record " + type.getSimpleName() + " has no field " + name + ".");
    }
    return construct(arguments);
  }

  private E construct(Object[] arguments) {
    try {
      Constructor<E> constructor = type.getDeclaredConstructor(Arrays.stream(type.getRecordComponents())
          .map(RecordComponent::getType).toArray(Class<?>[]::new));
      return constructor.newInstance(arguments);
    } catch (ReflectiveOperationException failed) {
      throw new IllegalArgumentException("The values do not make a " + type.getSimpleName() + ".", failed);
    }
  }

  private static String snake(String camel) {
    StringBuilder snake = new StringBuilder();
    for (char c : camel.toCharArray()) {
      if (Character.isUpperCase(c)) {
        snake.append('_').append(Character.toLowerCase(c));
      } else {
        snake.append(c);
      }
    }
    return snake.toString();
  }
}
