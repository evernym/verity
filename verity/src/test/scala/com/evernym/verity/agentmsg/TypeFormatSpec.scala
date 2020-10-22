package com.evernym.verity.agentmsg

import com.evernym.verity.actor.agent.TypeFormat
import com.evernym.verity.actor.agent.TypeFormat._
import com.evernym.verity.agentmsg.msgcodec._
import com.evernym.verity.testkit.BasicSpec


class TypeFormatSpec extends BasicSpec {
  "toString" - {
    "has expected strings" in {
      NOOP_TYPE_FORMAT.toString       shouldBe "noop"
      LEGACY_TYPE_FORMAT.toString     shouldBe "0.5"
      STANDARD_TYPE_FORMAT.toString   shouldBe "1.0"
    }
  }
  "fromString" - {
    "can create type from string" in {
      TypeFormat.fromString("noop") shouldBe NOOP_TYPE_FORMAT
      TypeFormat.fromString("0.5")  shouldBe LEGACY_TYPE_FORMAT
      TypeFormat.fromString("1.0")  shouldBe STANDARD_TYPE_FORMAT

      intercept[UnknownFormatType] {
        TypeFormat.fromString("SDF") shouldBe STANDARD_TYPE_FORMAT
      }
    }
  }

}
