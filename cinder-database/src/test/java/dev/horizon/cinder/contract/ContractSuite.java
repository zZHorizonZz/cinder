package dev.horizon.cinder.contract;

import org.junit.jupiter.api.Nested;

public abstract class ContractSuite {

  protected abstract TestDatabase testDatabase();

  @Nested
  class Backend extends BackendContractTest {

    @Override
    protected TestDatabase testDatabase() {
      return ContractSuite.this.testDatabase();
    }
  }

  @Nested
  class Computed extends ComputedContractTest {

    @Override
    protected TestDatabase testDatabase() {
      return ContractSuite.this.testDatabase();
    }
  }

  @Nested
  class Inner extends InnerContractTest {

    @Override
    protected TestDatabase testDatabase() {
      return ContractSuite.this.testDatabase();
    }
  }

  @Nested
  class Trigger extends TriggerContractTest {

    @Override
    protected TestDatabase testDatabase() {
      return ContractSuite.this.testDatabase();
    }
  }

  @Nested
  class Relation extends RelationContractTest {

    @Override
    protected TestDatabase testDatabase() {
      return ContractSuite.this.testDatabase();
    }
  }

  @Nested
  class Input extends InputContractTest {

    @Override
    protected TestDatabase testDatabase() {
      return ContractSuite.this.testDatabase();
    }
  }

  @Nested
  class Read extends ReadContractTest {

    @Override
    protected TestDatabase testDatabase() {
      return ContractSuite.this.testDatabase();
    }
  }

  @Nested
  class Write extends WriteContractTest {

    @Override
    protected TestDatabase testDatabase() {
      return ContractSuite.this.testDatabase();
    }
  }

  @Nested
  class Transaction extends TransactionContractTest {

    @Override
    protected TestDatabase testDatabase() {
      return ContractSuite.this.testDatabase();
    }
  }

  @Nested
  class Page extends PageContractTest {

    @Override
    protected TestDatabase testDatabase() {
      return ContractSuite.this.testDatabase();
    }
  }

  @Nested
  class Include extends IncludeContractTest {

    @Override
    protected TestDatabase testDatabase() {
      return ContractSuite.this.testDatabase();
    }
  }

  @Nested
  class Stream extends StreamContractTest {

    @Override
    protected TestDatabase testDatabase() {
      return ContractSuite.this.testDatabase();
    }
  }

  @Nested
  class Aggregate extends AggregateContractTest {

    @Override
    protected TestDatabase testDatabase() {
      return ContractSuite.this.testDatabase();
    }
  }

  @Nested
  class Extension extends ExtensionContractTest {

    @Override
    protected TestDatabase testDatabase() {
      return ContractSuite.this.testDatabase();
    }
  }
}
