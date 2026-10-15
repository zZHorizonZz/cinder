# Write an extension

An extension implements `Extension`, and overrides the points that it uses. These examples each use one point. For
every point, refer to [Extensions](../concepts/extensions.md).

## Obfuscate a field

A codec stores a value of a field in another form, and gives the entity the original.

1. Mark the fields in the schema. The protobuf extension gives each custom field option as the attribute of its full
   name, so the schema opts in per field:

   ```proto
   extend google.protobuf.FieldOptions {
     bool obfuscated = 58100;
   }

   message Customer {
     option (leadseal.v1.table) = {name: "customers"};

     string id = 1 [(leadseal.v1.column).primary_key = true];
     string email = 2 [(obfuscation.v1.obfuscated) = true];
   }
   ```

2. Write the codec. It reads the attribute, not the protobuf option, so it works for every model source:

   ```java
   public final class ObfuscationExtension implements Extension, TypeCodec {

     public static final Attribute<Boolean> OBFUSCATED = Attribute.of("obfuscation.v1.obfuscated", Boolean.class);

     private final Cipher cipher;

     public ObfuscationExtension(Cipher cipher) {
       this.cipher = cipher;
     }

     @Override
     public List<TypeCodec> codecs() {
       return List.of(this);
     }

     @Override
     public boolean applies(Field<?, ?> field) {
       return field.attribute(OBFUSCATED).orElse(false);
     }

     @Override
     public Object write(Field<?, ?> field, Object value) {
       return cipher.encrypt((String) value);
     }

     @Override
     public Object read(Field<?, ?> field, Object stored) {
       return cipher.decrypt((String) stored);
     }
   }
   ```

   `ProtobufField.attribute(ObfuscationProto.obfuscated)` gives the same attribute from the generated option.

## Fill in values

A part changes each row that a write sends, in `write`. The row is `Assignments`: the values of each entity of an
insert, or the changes of an update. A value from Java is a plain value. A value that the database computes is an
expression, and the write reads it back with `RETURNING`, so the entity that it gives has the value.

This extension gives a UUID to each key field that its option marks, unless the entity has one:

```java
public final class KeysExtension implements Extension {

  public static final Attribute<Boolean> GENERATED = Attribute.of("keys.v1.generated", Boolean.class);

  @Override
  public List<Part> parts(Model<?> model) {
    return model.key().stream().filter(field -> field.attribute(GENERATED).orElse(false))
        .<Part>map(field -> new Key(field.name())).toList();
  }

  private record Key(String field) implements Part {

    @Override
    public Assignments write(Assignments row, Context<?> context) {
      return context.kind() == Call.Kind.INSERT && row.value(field).filter(id -> !id.toString().isEmpty()).isEmpty()
          ? row.set(field, UUID.randomUUID().toString())
          : row;
    }
  }
}
```

This one has the database stamp the times of each row:

```java
public final class TimesExtension implements Extension, Part {

  @Override
  public List<Part> parts(Model<?> model) {
    return model.field("create_time").isPresent() || model.field("update_time").isPresent() ? List.of(this)
        : List.of();
  }

  @Override
  public Assignments write(Assignments row, Context<?> context) {
    Model<?> model = context.model();
    Assignments created = context.kind() == Call.Kind.INSERT && model.field("create_time").isPresent()
        ? row.set("create_time", new Now())
        : row;
    return model.field("update_time").isPresent() ? created.set("update_time", new Now()) : created;
  }

  private record Now() implements Expression {

    @Override
    public void render(StatementWriter sql) {
      sql.append("CURRENT_TIMESTAMP");
    }
  }
}
```

A version works the same way, with `row.increment("version", 1)` on each update.

## Keep tenants apart

A part can narrow the statements of its model, in `shape`, with Basepoint's `statement.where(filter)`. It joins the
filter of the statement, or adds one. The query of an include is an operation of the related model, so the parts of
that model narrow the related rows.

This extension gives each new row of a model with a `tenant` field the current tenant, keeps updates from moving a
row to another tenant, and narrows every other statement to the current tenant:

```java
public final class TenancyExtension implements Extension, Part {

  private final Supplier<String> tenant;

  public TenancyExtension(Supplier<String> tenant) {
    this.tenant = tenant;
  }

  @Override
  public List<Part> parts(Model<?> model) {
    return model.field("tenant").isPresent() ? List.of(this) : List.of();
  }

  @Override
  public Assignments write(Assignments row, Context<?> context) {
    return context.kind() == Call.Kind.INSERT ? row.set("tenant", tenant.get()) : row.without("tenant");
  }

  @Override
  public Statement shape(Statement statement, Context<?> context) {
    return context.kind() == Call.Kind.INSERT ? statement : statement.where(eq("tenant", tenant.get()));
  }
}
```

An insert has no filter, so `shape` leaves its statement as it is.

## Measure each statement

An interceptor wraps the run of each statement, and calls the next one:

```java
public final class MetricsExtension implements Extension, Interceptor {

  private final MeterRegistry registry;

  public MetricsExtension(MeterRegistry registry) {
    this.registry = registry;
  }

  @Override
  public List<Interceptor> interceptors() {
    return List.of(this);
  }

  @Override
  public <R> Future<R> intercept(Call<R> call, Chain<R> next) {
    Timer.Sample sample = Timer.start(registry);
    return next.proceed(call).andThen(done -> sample.stop(registry.timer("cinder.statement", "table",
        call.model().table(), "kind", call.kind().name())));
  }
}
```

## Change the SQL of every statement

A Basepoint decorator of every node, such as one that writes a clause in the syntax of a dialect, goes in
`decorator()`. It chains with the decorator of the dialect.

## Related resources

- [Extensions](../concepts/extensions.md)
- [Add an operation](add-an-operation.md)
