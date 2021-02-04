package com.evernym.verity.agentmsg.msgpacker

import com.evernym.verity.testkit.BasicSpec

class AgentMsgPackagingUtilSpec
  extends BasicSpec {

  "AgentMsgPackagingUtil" - {
    "when asked to buildRoutingKeys" - {

      "with non empty recip key and non empty routing keys" - {
        "should prepare it correctly" in {
          val actualRoutingKeys = AgentMsgPackagingUtil.buildRoutingKeys(
            "recipKey1", Seq("routingKey1", "routingKey2"))
          val expectedRoutingKeys = Seq("recipKey1", "routingKey1", "routingKey2")

          actualRoutingKeys shouldBe expectedRoutingKeys
        }
      }

      "with non empty recip key and non empty routing keys with recip key in it" - {
        "should prepare it correctly" in {
          val actualRoutingKeys = AgentMsgPackagingUtil.buildRoutingKeys(
            "recipKey1", Seq("recipKey1", "routingKey1", "routingKey2"))
          val expectedRoutingKeys = Seq("recipKey1", "routingKey1", "routingKey2")

          actualRoutingKeys shouldBe expectedRoutingKeys
        }
      }

      "with non empty recip key and empty routing keys" - {
        "should prepare it correctly" in {
          val actualRoutingKeys = AgentMsgPackagingUtil.buildRoutingKeys(
            "recipKey1", Seq.empty)
          val expectedRoutingKeys = Seq.empty
          actualRoutingKeys shouldBe expectedRoutingKeys
        }
      }
    }
  }

}
