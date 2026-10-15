package dev.horizon.cinder.sqlserver;

import dev.horizon.cinder.contract.ContractSuite;
import dev.horizon.cinder.contract.TestDatabase;

class SqlServerContractTest extends ContractSuite {

  private static final TestDatabase DATABASE = new SqlServerTestDatabase();

  @Override
  protected TestDatabase testDatabase() {
    return DATABASE;
  }
}
