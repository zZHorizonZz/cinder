package dev.horizon.cinder.mariadb;

import dev.horizon.cinder.contract.ContractSuite;
import dev.horizon.cinder.contract.TestDatabase;

class MariaDbContractTest extends ContractSuite {

  private static final TestDatabase DATABASE = new MariaDbTestDatabase();

  @Override
  protected TestDatabase testDatabase() {
    return DATABASE;
  }
}
