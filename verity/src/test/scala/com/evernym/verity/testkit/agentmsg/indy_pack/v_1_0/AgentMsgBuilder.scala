package com.evernym.verity.testkit.agentmsg.indy_pack.v_1_0

import com.evernym.verity.agentmsg.msgpacker.PackedMsg
import com.evernym.verity.protocol.engine.Constants.MFV_1_0
import com.evernym.verity.protocol.engine.{MPV_INDY_PACK, MsgPackVersion}
import com.evernym.verity.testkit.agentmsg.AgentMsgHelper
import com.evernym.verity.testkit.mock.agent.MockAgent
import com.evernym.verity.testkit.util.TestComMethod

trait AgentMsgBuilder { this: AgentMsgHelper with MockAgent with AgentMsgHelper =>

  object v_1_0_req {

    implicit val msgPackVersion: MsgPackVersion = MPV_INDY_PACK

    def prepareUpdateComMethodMsgForAgent(cm: TestComMethod): PackedMsg = {
      prepareUpdateComMethodMsgForAgentBase(MFV_1_0, cm)
    }
  }

}