package com.evernym.verity.testkit.agentmsg.indy_pack.v_1_0

import com.evernym.verity.actor.agent.MsgPackFormat
import com.evernym.verity.actor.agent.MsgPackFormat.MPF_INDY_PACK
import com.evernym.verity.protocol.engine.Constants.MFV_1_0
import com.evernym.verity.testkit.agentmsg.AgentMsgHelper
import com.evernym.verity.testkit.util.TestComMethod
import com.evernym.verity.actor.wallet.PackedMsg
import com.evernym.verity.testkit.mock.agent.MockAgent

trait AgentMsgBuilder { this: AgentMsgHelper with MockAgent with AgentMsgHelper =>

  object v_1_0_req {

    implicit val msgPackFormat: MsgPackFormat = MPF_INDY_PACK

    def prepareUpdateComMethodMsgForAgent(cm: TestComMethod): PackedMsg = {
      prepareUpdateComMethodMsgForAgentBase(MFV_1_0, cm)
    }
  }

}