package dev.horizon.cinder.sqlite;

import dev.horizon.cinder.contract.ContractSuite;
import dev.horizon.cinder.contract.TestDatabase;

class SqliteContractTest extends ContractSuite {

  private static final TestDatabase DATABASE = new SqliteTestDatabase();

  @Override
  protected TestDatabase testDatabase() {
    return DATABASE;
  }
}
