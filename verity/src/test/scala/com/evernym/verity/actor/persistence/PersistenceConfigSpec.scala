package com.evernym.verity.actor.persistence

import com.evernym.verity.testkit.BasicSpec

class PersistenceConfigSpec extends BasicSpec {

  "PersistenceConfig" - {

    "when tried to construct valid PersistenceConfig" - {
      "should be created successfully" in {
        testValidPersistenceConfig()
      }
    }

    "when tried to construct invalid PersistenceConfig" - {
      "should throw appropriate exception" in {
        testAllowOnlyEventAndAllowOnlySnapshot()
        testAllowOnlyEventAndSnapshotEveryNEvents()
        testAllowOnlySnapshotAndSnapshotEveryNEvents()
      }
    }
  }

  def testValidPersistenceConfig(): Unit = {
    PersistenceConfig(
      allowOnlyEvents = false,
      allowOnlySnapshots = false,
      snapshotEveryNEvents = Option(1),
      keepNSnapshots = Option(1),
      deleteEventsOnSnapshot = false
    )

    PersistenceConfig(
      allowOnlyEvents = false,
      allowOnlySnapshots = false,
      snapshotEveryNEvents = Option(1),
      keepNSnapshots = Option(1),
      deleteEventsOnSnapshot = true
    )

    PersistenceConfig(
      allowOnlyEvents = true,
      allowOnlySnapshots = false,
      snapshotEveryNEvents = None,
      keepNSnapshots = None,
      deleteEventsOnSnapshot = false
    )

    PersistenceConfig(
      allowOnlyEvents = false,
      allowOnlySnapshots = true,
      snapshotEveryNEvents = None,
      keepNSnapshots = Option(1),
      deleteEventsOnSnapshot = true
    )
  }

  def testAllowOnlyEventAndAllowOnlySnapshot(): Unit = {
    //allowOnlyEvents and allowOnlySnapshots both can't be true
    val ex = intercept[RuntimeException] {
      PersistenceConfig(
        allowOnlyEvents = true,
        allowOnlySnapshots = true,
        snapshotEveryNEvents = Option(1),
        keepNSnapshots = Option(1),
        deleteEventsOnSnapshot = false
      )
    }
    ex.getMessage shouldBe "requirement failed: 'allowOnlyEvents' and 'allowOnlySnapshots' are conflicting"
  }

  def testAllowOnlyEventAndSnapshotEveryNEvents(): Unit = {
    //if allowOnlyEvents is true then there shouldn't be any snapshot taken
    val ex = intercept[RuntimeException] {
      PersistenceConfig(
        allowOnlyEvents = true,
        allowOnlySnapshots = false,
        snapshotEveryNEvents = Option(1),
        keepNSnapshots = Option(1),
        deleteEventsOnSnapshot = false
      )
    }
    ex.getMessage shouldBe "requirement failed: 'allowOnlyEvents' and 'snapshotEveryNEvents' are conflicting"
  }

  def testAllowOnlySnapshotAndSnapshotEveryNEvents(): Unit = {
    //if allowOnlySnapshots is true then the snapshotEveryNEvents can't be non empty and non zero
    val ex = intercept[RuntimeException] {
      PersistenceConfig(
        allowOnlyEvents = false,
        allowOnlySnapshots = true,
        snapshotEveryNEvents = Option(1),
        keepNSnapshots = Option(1),
        deleteEventsOnSnapshot = false
      )
    }
    ex.getMessage shouldBe "requirement failed: 'allowOnlySnapshots' and 'snapshotEveryNEvents' are conflicting"
  }

}
