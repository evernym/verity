package com.evernym.verity.actor.resourceusagethrottling

import com.evernym.verity.actor.cluster_singleton.resourceusagethrottling.blocking.BlockingDetail
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.util.TimeZoneUtil.getCurrentUTCZonedDateTime

class BlockingDetailSpec
  extends BasicSpec {

  def emptyBlockingDetail: BlockingDetail = BlockingDetail(None, None, None, None)
  var bd: BlockingDetail = emptyBlockingDetail

  "BlockingDetail" - {
    "when only blockFrom is set" - {
      "and tested isBlocked method for few future times" - {
        "should respond with true" in {
          val curDateTime = getCurrentUTCZonedDateTime
          bd = emptyBlockingDetail.copy(blockFrom = Some(curDateTime))
          bd.isBlocked(curDateTime.plusSeconds(1)) shouldBe true
          bd.isBlocked(curDateTime.plusSeconds(11)) shouldBe true
        }
      }
    }

    "when blockFrom and blockTill both are set" - {
      "and tested isBlocked method for few future times" - {
        "should respond accordingly" in {
          val curDateTime = getCurrentUTCZonedDateTime
          bd = emptyBlockingDetail.copy(blockFrom = Some(curDateTime),
            blockTill = Some(curDateTime.plusSeconds(10)))
          bd.isBlocked(curDateTime.plusSeconds(1)) shouldBe true
          bd.isBlocked(curDateTime.plusSeconds(11)) shouldBe false
        }
      }
    }

    "when only unblockFrom is set" - {
      "and tested few helper methods for few future times" - {
        "should respond accordingly" in {
          val curDateTime = getCurrentUTCZonedDateTime
          bd = emptyBlockingDetail.copy(unblockFrom = Some(curDateTime))
          bd.isInBlockingPeriod(curDateTime.plusSeconds(1)) shouldBe false
          bd.isInUnblockingPeriod(curDateTime.plusSeconds(1)) shouldBe true
          bd.isBlocked(curDateTime.plusSeconds(1)) shouldBe false
        }
      }
    }

    "when unblockFrom and unblockTill both are set" - {
      "and tested few helper methods for few future times" - {
        "should respond accordingly" in {
          val curDateTime = getCurrentUTCZonedDateTime
          bd = emptyBlockingDetail.copy(unblockFrom = Some(curDateTime),
            unblockTill = Option(curDateTime.plusSeconds(10)))
          bd.isInBlockingPeriod(curDateTime.plusSeconds(1)) shouldBe false
          bd.isInUnblockingPeriod(curDateTime.plusSeconds(1)) shouldBe true
          bd.isBlocked(curDateTime.plusSeconds(1)) shouldBe false
          bd.isInBlockingPeriod(curDateTime.plusSeconds(11)) shouldBe false
          bd.isInUnblockingPeriod(curDateTime.plusSeconds(11)) shouldBe false
          bd.isBlocked(curDateTime.plusSeconds(11)) shouldBe false
        }
      }
    }

    "when blockFrom and unblockFrom both are set" - {
      "and tested few helper methods for few future times" - {
        "should respond accordingly" in {
          val curDateTime = getCurrentUTCZonedDateTime
          bd = emptyBlockingDetail.copy(blockFrom = Some(curDateTime), unblockFrom = Some(curDateTime))
          bd.isInBlockingPeriod(curDateTime.plusSeconds(1)) shouldBe true
          bd.isInUnblockingPeriod(curDateTime.plusSeconds(1)) shouldBe true
          bd.isBlocked(curDateTime.plusSeconds(1)) shouldBe false //unblocking period takes precedence
        }
      }
    }

    "when blockFrom, unblockFrom and unblockTill are set" - {
      "and tested few helper methods for few future times" - {
        "should respond accordingly" in {
          val curDateTime = getCurrentUTCZonedDateTime
          bd = emptyBlockingDetail.copy(blockFrom = Some(curDateTime), unblockFrom = Some(curDateTime),
            unblockTill = Some(curDateTime.plusSeconds(10)))
          bd.isInBlockingPeriod(curDateTime.plusSeconds(1)) shouldBe true
          bd.isInBlockingPeriod(curDateTime.plusSeconds(11)) shouldBe true
          bd.isInUnblockingPeriod(curDateTime.plusSeconds(11)) shouldBe false
          bd.isBlocked(curDateTime.plusSeconds(11)) shouldBe true
        }
      }
    }
  }
}
