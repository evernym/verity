package com.evernym.verity.actor.cluster_singleton.resourceusagethrottling.warning

import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.util.TimeZoneUtil.getCurrentUTCZonedDateTime

class WarningDetailSpec
  extends BasicSpec {

  def emptyWarningDetail: WarningDetail = WarningDetail(None, None, None, None)
  var wd: WarningDetail = emptyWarningDetail

  "WarningDetail" - {

    "when only warnFrom is set" - {
      "and tested isWarned method for few future times" - {
        "should respond with true" in {
          val curDateTime = getCurrentUTCZonedDateTime
          wd = emptyWarningDetail.copy(warnFrom = Some(curDateTime))
          wd.isWarned(curDateTime.plusSeconds(1)) shouldBe true
          wd.isWarned(curDateTime.plusSeconds(11)) shouldBe true
        }
      }
    }

    "when warnFrom and warnTill both are set" - {
      "and tested isWarned method for few future times" - {
        "should respond accordingly" in {
          val curDateTime = getCurrentUTCZonedDateTime
          wd = emptyWarningDetail.copy(
            warnFrom = Some(curDateTime),
            warnTill = Some(curDateTime.plusSeconds(10))
          )
          wd.isWarned(curDateTime.plusSeconds(1)) shouldBe true
          wd.isWarned(curDateTime.plusSeconds(11)) shouldBe false
        }
      }
    }

    "when only unwarnFrom is set" - {
      "and tested few helper methods for few future times" - {
        "should respond accordingly" in {
          val curDateTime = getCurrentUTCZonedDateTime
          wd = emptyWarningDetail.copy(unwarnFrom = Some(curDateTime))
          wd.isUnwarned(curDateTime.plusSeconds(1)) shouldBe true
          wd.isUnwarned(curDateTime.plusSeconds(11)) shouldBe true
        }
      }
    }

    "when unwarnFrom and unwarnTill both are set" - {
      "and tested few helper methods for few future times" - {
        "should respond accordingly" in {
          val curDateTime = getCurrentUTCZonedDateTime
          wd = emptyWarningDetail.copy(
            unwarnFrom = Some(curDateTime),
            unwarnTill = Option(curDateTime.plusSeconds(10))
          )
          wd.isUnwarned(curDateTime.plusSeconds(1)) shouldBe true
          wd.isUnwarned(curDateTime.plusSeconds(11)) shouldBe false
        }
      }
    }

    "when warnFrom and unwarnFrom both are set" - {
      "and tested few helper methods for few future times" - {
        "should respond accordingly" in {
          val curDateTime = getCurrentUTCZonedDateTime
          wd = emptyWarningDetail.copy(
            warnFrom = Some(curDateTime),
            unwarnFrom = Some(curDateTime)
          )
          wd.isWarned(curDateTime.plusSeconds(1)) shouldBe false // unwarning period takes precedence
          wd.isUnwarned(curDateTime.plusSeconds(1)) shouldBe true // unwarning period takes precedence
        }
      }
    }

    "when warnFrom, unwarnFrom and unwarnTill are set" - {
      "and tested few helper methods for few future times" - {
        "should respond accordingly" in {
          val curDateTime = getCurrentUTCZonedDateTime
          wd = emptyWarningDetail.copy(
            warnFrom = Some(curDateTime),
            unwarnFrom = Some(curDateTime),
            unwarnTill = Some(curDateTime.plusSeconds(10))
          )
          wd.isWarned(curDateTime.plusSeconds(1)) shouldBe false
          wd.isUnwarned(curDateTime.plusSeconds(1)) shouldBe true
          wd.isWarned(curDateTime.plusSeconds(11)) shouldBe true
          wd.isUnwarned(curDateTime.plusSeconds(11)) shouldBe false
        }
      }
    }

  }

}
