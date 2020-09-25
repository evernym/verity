package com.evernym.verity.protocol.protocols.writeCredentialDefinition.v_0_6

import java.util.UUID

import com.evernym.verity.agentmsg.buildAgentMsg
import com.evernym.verity.agentmsg.msgcodec.StandardTypeFormat
import com.evernym.verity.protocol.engine.DEFAULT_THREAD_ID
import com.evernym.verity.testkit.BasicSpec


class MessageSpec extends BasicSpec {
  "SchemaProtocol" - {
    "msg types can convert to JSON" - {
      "StatusReport" in {
        val credDefId = "CEApD4x6dH94K6WgQsZ9A4:2:license:0.1"
        val msg = StatusReport(credDefId)
        val json = buildAgentMsg(
          msg,
          UUID.randomUUID().toString,
          DEFAULT_THREAD_ID,
          CredDefDefinition,
          StandardTypeFormat
        )
        assert(json.jsonStr.contains(credDefId))
      }
    }
  }
}
