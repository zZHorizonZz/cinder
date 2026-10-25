# Changelog

## [0.4.0](https://github.com/zZHorizonZz/cinder/compare/v0.3.1...v0.4.0) (2026-10-25)


### ⚠ BREAKING CHANGES

* Backend.type() is now Backend.prototype(), which gives an entity of the type. Database.model and ModelSource.model take an entity, and Relation.target is an entity of the related type. The operations by key take a table whose key has one column; a record of the key fields is no longer a key, so select such a row with a filter on each of them.

### Features

* name a backend's model by an entity of its type, not its class, and select a row of a key of several columns by a filter. ([c176bdf](https://github.com/zZHorizonZz/cinder/commit/c176bdf14f3c13f106468e755dac3a3ecf258455))

## [0.3.1](https://github.com/zZHorizonZz/cinder/compare/v0.3.0...v0.3.1) (2026-10-23)


### Fixes

* refuse a text filter or order that names a field of no column as an invalid query, not an illegal argument. ([7756158](https://github.com/zZHorizonZz/cinder/commit/7756158897f14b7c80ac472bfb4e8257b21a528f))
* store an empty binary value as null, on every database, as SQL Server cannot take a zero-length one. ([abdbf34](https://github.com/zZHorizonZz/cinder/commit/abdbf346e660172515101daf0c98deb1530da877))

## [0.3.0](https://github.com/zZHorizonZz/cinder/compare/v0.2.0...v0.3.0) (2026-10-21)


### Features

* ProtobufMapper converts a value of an API to the columns of a protobuf row, and back. ([e846e70](https://github.com/zZHorizonZz/cinder/commit/e846e70e081451ec3e923da199516aa11555fae6))

## [0.2.0](https://github.com/zZHorizonZz/cinder/compare/v0.1.0...v0.2.0) (2026-10-21)


### Features

* filter by a key of a field that the database stores as a JSON object. ([887261b](https://github.com/zZHorizonZz/cinder/commit/887261b81b91346c44814bc90a7efd7cc8996c0a))

## 0.1.0 (2026-10-16)


### Features

* backends of entities on PostgreSQL, MariaDB, SQL Server and SQLite, with protobuf messages as an extension ([c4fa29f](https://github.com/zZHorizonZz/cinder/commit/c4fa29f94b5815928a23ec7e798fd63525fb381b))
