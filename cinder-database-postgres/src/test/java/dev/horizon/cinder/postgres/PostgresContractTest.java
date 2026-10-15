package dev.horizon.cinder.postgres;

import dev.horizon.cinder.contract.ContractSuite;
import dev.horizon.cinder.contract.TestDatabase;

class PostgresContractTest extends ContractSuite {

  private static final TestDatabase DATABASE = new PostgresTestDatabase();

  @Override
  protected TestDatabase testDatabase() {
    return DATABASE;
  }
}
