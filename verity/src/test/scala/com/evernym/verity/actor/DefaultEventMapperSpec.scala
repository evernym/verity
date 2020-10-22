package com.evernym.verity.actor

import com.evernym.verity.actor.persistence.object_code_mapper.DefaultObjectCodeMapper
import com.evernym.verity.testkit.BasicSpec

class DefaultEventMapperSpec extends BasicSpec {

  "DefaultEventMapper" - {
    "when called getCodeFromClass for an event" - {
      "should be able to provide valid event code" in {
        val keyCreated = KeyCreated()
        DefaultObjectCodeMapper.codeFromObject(keyCreated) shouldBe 1
      }
    }
  }

}
