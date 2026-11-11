package dev.horizon.cinder.sqlserver;

/**
 * A column of the key of a table, with the type that SQL Server declares for its values.
 *
 * @param name the name of the column
 * @param type the type, such as {@code BIGINT}
 */
record KeyColumn(String name, String type) {
}
