package com.evernym.verity.agentmsg

import com.evernym.verity.agentmsg.msgcodec._
import com.evernym.verity.testkit.BasicSpec


class TypeFormatSpec extends BasicSpec {
  "toString" - {
    "has expected strings" in {
      NoopTypeFormat.toString       shouldBe "noop"
      LegacyTypeFormat.toString     shouldBe "0.5"
      StandardTypeFormat.toString   shouldBe "1.0"
    }
  }
  "fromString" - {
    "can create type from string" in {
      TypeFormat.fromString("noop") shouldBe NoopTypeFormat
      TypeFormat.fromString("0.5") shouldBe LegacyTypeFormat
      TypeFormat.fromString("1.0") shouldBe StandardTypeFormat

      intercept[UnknownFormatType] {
        TypeFormat.fromString("SDF") shouldBe StandardTypeFormat
      }
    }
  }

}
