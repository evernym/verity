package com.evernym.verity.actor

import com.evernym.verity.actor.event.serializer.DefaultEventMapper
import com.evernym.verity.testkit.BasicSpec

class DefaultEventMapperSpec extends BasicSpec {

  "DefaultEventMapper" - {
    "when called getCodeFromClass for an event" - {
      "should be able to provide valid event code" in {
        val writeCredDefReqReceived = KeyCreated()
        DefaultEventMapper.getCodeFromClass(writeCredDefReqReceived) shouldBe 1
      }
    }
  }

}
