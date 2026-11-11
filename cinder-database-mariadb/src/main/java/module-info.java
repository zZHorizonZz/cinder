/**
 * The MariaDB driver of Cinder.
 */
module dev.horizon.cinder.mariadb {
  requires transitive dev.horizon.cinder;
  requires transitive dev.horizon.basepoint.mariadb;

  exports dev.horizon.cinder.mariadb;
}
