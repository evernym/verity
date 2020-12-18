package com.evernym.verity.actor.persistence

import akka.persistence.SnapshotSelectionCriteria
import com.evernym.verity.testkit.BasicSpec

class SnapshotConfigSpec extends BasicSpec {

  "SnapshotConfig" - {

    "when tried to construct valid SnapshotConfig" - {
      "should be created successfully" in {
        testValidPersistenceConfig()
      }
    }

    "when tried to construct invalid SnapshotConfig" - {
      "should throw appropriate exception" in {
        testInvalidSnapshotConfig()
      }
    }

    "when tried to construct delete snapshot select criteria" - {
      "should be able to create it as per configuration" in {
        val pc1 = SnapshotConfig(
          snapshotEveryNEvents = Option(1),
          keepNSnapshots = Option(1),
          deleteEventsOnSnapshot = false
        )
        pc1.getDeleteSnapshotCriteria(1) shouldBe None
        pc1.getDeleteSnapshotCriteria(2) shouldBe Some(SnapshotSelectionCriteria(maxSequenceNr = 1, minSequenceNr = 1))
        pc1.getDeleteSnapshotCriteria(3) shouldBe Some(SnapshotSelectionCriteria(maxSequenceNr = 2, minSequenceNr = 2))

        val pc2 = SnapshotConfig(
          snapshotEveryNEvents = Option(2),
          keepNSnapshots = Option(2),
          deleteEventsOnSnapshot = false
        )
        pc2.getDeleteSnapshotCriteria(2) shouldBe None
        pc2.getDeleteSnapshotCriteria(4) shouldBe None
        pc2.getDeleteSnapshotCriteria(6) shouldBe Some(SnapshotSelectionCriteria(maxSequenceNr = 3, minSequenceNr = 2))

        val pc3 = SnapshotConfig(
          snapshotEveryNEvents = Option(3),
          keepNSnapshots = Option(2),
          deleteEventsOnSnapshot = false
        )
        pc3.getDeleteSnapshotCriteria(3) shouldBe None
        pc3.getDeleteSnapshotCriteria(6) shouldBe None
        pc3.getDeleteSnapshotCriteria(9) shouldBe Some(SnapshotSelectionCriteria(maxSequenceNr = 5, minSequenceNr = 3))
      }
    }
  }



  def testValidPersistenceConfig(): Unit = {
    SnapshotConfig(
      snapshotEveryNEvents = Option(1),
      keepNSnapshots = Option(1),
      deleteEventsOnSnapshot = false
    )

    SnapshotConfig(
      snapshotEveryNEvents = Option(1),
      keepNSnapshots = Option(1),
      deleteEventsOnSnapshot = true
    )

    //manually snapshotted
    SnapshotConfig(
      snapshotEveryNEvents = None,
      keepNSnapshots = Option(1),
      deleteEventsOnSnapshot = true
    )

    //no snapshot at all
    SnapshotConfig(
      snapshotEveryNEvents = None,
      keepNSnapshots = None,
      deleteEventsOnSnapshot = false
    )
  }

  def testInvalidSnapshotConfig(): Unit = {
    //allowOnlyEvents and allowOnlySnapshots both can't be true
    val ex1 = intercept[RuntimeException] {
      SnapshotConfig(
        snapshotEveryNEvents = Option(1),
        keepNSnapshots = None,
        deleteEventsOnSnapshot = false
      )
    }
    ex1.getMessage shouldBe "requirement failed: 'snapshotEveryNEvents' and 'keepNSnapshots' are conflicting"

    val ex2 = intercept[RuntimeException] {
      SnapshotConfig(
        snapshotEveryNEvents = Option(1),
        keepNSnapshots = Option(0),
        deleteEventsOnSnapshot = false
      )
    }
    ex2.getMessage shouldBe "requirement failed: 'snapshotEveryNEvents' and 'keepNSnapshots' are conflicting"

    val ex3 = intercept[RuntimeException] {
      SnapshotConfig(
        snapshotEveryNEvents = None,
        keepNSnapshots = Option(2),
        deleteEventsOnSnapshot = false
      )
    }
    ex3.getMessage shouldBe "requirement failed: 'keepNSnapshots' and 'snapshotEveryNEvents' are conflicting"
  }

}
