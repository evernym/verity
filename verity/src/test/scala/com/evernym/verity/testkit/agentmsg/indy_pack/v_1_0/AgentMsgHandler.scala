package com.evernym.verity.testkit.agentmsg.indy_pack.v_1_0

import com.evernym.verity.testkit.agentmsg.AgentMsgHelper
import com.evernym.verity.testkit.mock.HasCloudAgent
import com.evernym.verity.testkit.mock.agent.MockAgent

/**
 * this will handle received/incoming/response agent messages
 */
trait AgentMsgHandler { this: AgentMsgHelper with MockAgent with HasCloudAgent =>

  object v_1_0_resp {

  }

}
