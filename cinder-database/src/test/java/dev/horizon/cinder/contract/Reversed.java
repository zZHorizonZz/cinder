package dev.horizon.cinder.contract;

import dev.horizon.cinder.Field;
import dev.horizon.cinder.TypeCodec;

final class Reversed implements TypeCodec {

  @Override
  public boolean applies(Field<?, ?> field) {
    return field.name().equals("name");
  }

  @Override
  public Object write(Field<?, ?> field, Object value) {
    return new StringBuilder((String) value).reverse().toString();
  }

  @Override
  public Object read(Field<?, ?> field, Object stored) {
    return new StringBuilder((String) stored).reverse().toString();
  }
}
