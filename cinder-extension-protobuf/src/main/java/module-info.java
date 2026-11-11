/**
 * The protobuf extension of Cinder: messages with the table options of Leadseal as entities.
 */
module dev.horizon.cinder.protobuf {
  requires transitive dev.horizon.cinder;
  requires transitive com.google.protobuf;
  requires dev.horizon.leadseal.protobuf;
  requires com.google.protobuf.util;

  exports dev.horizon.cinder.protobuf;
}
