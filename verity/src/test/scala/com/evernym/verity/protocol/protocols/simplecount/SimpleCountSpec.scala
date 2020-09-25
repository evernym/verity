package com.evernym.verity.protocol.protocols.simplecount

import com.evernym.verity.protocol.testkit.InteractionType.OneParty
import com.evernym.verity.protocol.testkit.{InteractionType, TestsProtocolsImpl}
import com.evernym.verity.testkit.BasicFixtureSpec


class SimpleCountSpec extends TestsProtocolsImpl(SimpleCountDefinition) with BasicFixtureSpec {

  override val defaultInteractionType: InteractionType = OneParty

  "SimpleCount" - {
    "can start" in { f =>

      f.alice ~ Start()

      f.alice.state shouldBe a[Counting]
    }

    "can count" in { f =>

      f.alice ~ Start()
      f.alice.state shouldBe a[Counting]

      f.alice ~ Increment()
      f.alice.state.data.count shouldBe 1

      f.alice ~ Increment()
      f.alice.state.data.count shouldBe 2
    }
  }
}

