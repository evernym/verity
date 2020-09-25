package com.evernym.verity.actor.agent.user

import com.evernym.verity.constants.Constants.{COM_METHOD_TYPE_FWD_PUSH, COM_METHOD_TYPE_PUSH}
import com.evernym.verity.protocol.engine.Constants.MTV_1_0
import com.evernym.verity.protocol.engine.{DID, MPV_INDY_PACK}
import com.evernym.verity.testkit.agentmsg.AgentMsgPackagingContext

class ConsumerAgentPairwiseBaseSpec_V_0_7 extends UserAgentPairwiseSpec_V_0_7 {

  implicit val msgPackagingContext: AgentMsgPackagingContext =
    AgentMsgPackagingContext(MPV_INDY_PACK, MTV_1_0, packForAgencyRoute = false)

  updateComMethodSpec()
}

trait UserAgentPairwiseSpec_V_0_7
  extends UserAgentPairwiseSpecScaffolding {

  implicit def msgPackagingContext: AgentMsgPackagingContext

  override def beforeAll(): Unit = {
    super.beforeAll()
    setupAgency()
    createUserAgent_0_7()
  }

  var agentPairwiseDID: DID = _

  val connId1New = "connIdNew1"

  def updateComMethodSpec(): Unit = {

    "when sent UPDATE_COM_METHOD msg to register a forward endpoint" - {
      "should respond with COM_METHOD_UPDATED msg" in {
        updateComMethod(COM_METHOD_TYPE_PUSH, testPushComMethod)
        updateComMethod(COM_METHOD_TYPE_FWD_PUSH, "localhost:7002")
        updateComMethod(COM_METHOD_TYPE_FWD_PUSH, "localhost:7002")
      }
    }
  }
}
