module dev.horizon.cinder.sqlite {
  requires transitive dev.horizon.cinder;
  requires transitive dev.horizon.basepoint.sqlite;
  requires java.sql;

  exports dev.horizon.cinder.sqlite;
}
