package com.evernym.verity.protocol.protocols.updateConfigs.v_0_6

import java.util.UUID

import com.evernym.verity.constants.Constants._
import com.evernym.verity.agentmsg.buildAgentMsg
import com.evernym.verity.agentmsg.msgcodec.StandardTypeFormat
import com.evernym.verity.protocol.engine.DEFAULT_THREAD_ID
import com.evernym.verity.testkit.BasicSpec


class MessageSpec extends BasicSpec {
  "UpdateConfigsProtocol" - {
    "msg types can convert to JSON" - {
      "StatusReport" in {
        val name = "test name"
        val logoUrl = "/logo.ico"
        val configs = Set(
          Config(NAME_KEY, name),
          Config(LOGO_URL_KEY, logoUrl))
        val msg = ConfigResult(configs)
        val json = buildAgentMsg(
          msg,
          UUID.randomUUID().toString,
          DEFAULT_THREAD_ID,
          UpdateConfigsDefinition,
          StandardTypeFormat
        )
        assert(json.jsonStr.contains(name))
        assert(json.jsonStr.contains(logoUrl))
      }
    }
  }
}
