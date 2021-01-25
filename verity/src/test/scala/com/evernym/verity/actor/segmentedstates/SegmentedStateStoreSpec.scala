package com.evernym.verity.actor.segmentedstates

import akka.actor.PoisonPill
import com.evernym.verity.actor._
import com.evernym.verity.actor.testkit.PersistentActorSpec
import com.evernym.verity.actor.testkit.checks.UNSAFE_IgnoreLog
import com.evernym.verity.testkit.BasicSpec

class SegmentedStateStoreSpec extends PersistentActorSpec with BasicSpec with ShardUtil {

  private lazy val ssRegion = createPersistentRegion("segment-state-region", SegmentedStateStore.props)

  lazy val ss1 = agentRegion("segment-address-1", ssRegion)
  lazy val ss2 = agentRegion("segment-address-2", ssRegion)

  "A segmented state store" - {

    "when received SaveSegmentedState for segment address 1" - {
      "should be able to store the state" taggedAs (UNSAFE_IgnoreLog) in {
        ss1 ! SaveSegmentedState("1", MappingAdded("1", "a"))
        expectMsgType[Some[SegmentedStateStored]]
      }
    }

    "when received same SaveSegmentedState for segment address 1" - {
      "should throw exception" in {
        ss1 ! SaveSegmentedState("1", MappingAdded("1", "a"))
        expectMsgType[ValidationError]
      }
    }

    "when received GetSegmentedState for segment address 1" - {
      "should be able to send value back" in {
        ss1 ! GetSegmentedState("1")
        val value = expectMsgType[Option[MappingAdded]]
        value shouldBe Option(MappingAdded("1", "a"))
      }
    }

    "when received SaveSegmentedState for segment address 2" - {
      "should be able to store the state" in {
        ss2 ! SaveSegmentedState("2", ProtocolIdDetailSet("connecting", "0.6", "222"))
        expectMsgType[Some[SegmentedStateStored]]
      }
    }

    "when received GetSegmentedState segment address 2" - {
      "should be able to send value back" in {
        ss2 ! GetSegmentedState("2")
        val value = expectMsgType[Option[ProtocolIdDetailSet]]
        value shouldBe Option(ProtocolIdDetailSet("connecting", "0.6", "222"))
      }
    }

    "when received GetSegmentedState cmd for unused address" - {
      "should be able to send None" in {
        agentRegion("other-address", ssRegion) ! GetSegmentedState("unused")
        val value = expectMsgType[Option[_]]
        value shouldBe None
      }
    }

    "when sent PoisonPill to segment address 2" - {
      "should not respond" taggedAs (UNSAFE_IgnoreLog) in {
        ss2 ! PoisonPill
        expectNoMessage()
      }
    }

    "when received GetSegmentedState cmd for segment address 2 (after it is stopped)" - {
      "should be able to send value back" in {
        ss2 ! GetSegmentedState("2")
        val value = expectMsgType[Option[ProtocolIdDetailSet]]
        value shouldBe Option(ProtocolIdDetailSet("connecting", "0.6", "222"))
      }
    }

  }
}
