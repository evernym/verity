package com.evernym.verity.actor.agent.user

import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.util2.Status.UNSUPPORTED_MSG_TYPE
import com.evernym.verity.actor.agent.MsgPackFormat.MPF_MSG_PACK
import com.evernym.verity.actor.agentRegion
import com.evernym.verity.constants.Constants.MTV_1_0
import com.evernym.verity.testkit.agentmsg.AgentMsgPackagingContext
import com.evernym.verity.testkit.util.TestConfigDetail
import com.evernym.verity.actor.wallet.PackedMsg
import com.evernym.verity.did.DidPair

import scala.concurrent.ExecutionContext

class UserAgentSpec_V_0_5 extends UserAgentSpecScaffolding {
  import mockEdgeAgent.v_0_5_req._
  import mockEdgeAgent.v_0_5_resp._

  implicit val msgPackagingContext: AgentMsgPackagingContext =
    AgentMsgPackagingContext(MPF_MSG_PACK, MTV_1_0, packForAgencyRoute = false)

  override def beforeAll(): Unit = {
    super.beforeAll()
    setupAgency()
  }

  userAgentBaseSpecs()

  def alltests(ua: agentRegion, userDIDPair: DidPair): Unit = {

    "User Agent" - {

      "when sent GET_CONFIGS msg with unsupported version" - {
        "should respond with unsupported version error msg" in {
          val msg = prepareGetAgentConfigsMsgForAgent(unsupportedVersion, Set(""))
          ua ! wrapAsPackedMsgParam(msg)
          expectError(UNSUPPORTED_MSG_TYPE.statusCode)
        }
      }

      "when sent GET_CONFIGS msg" - {
        "should respond with CONFIGS msg" in {
          val msg = prepareGetAgentConfigsMsgForAgent(Set(""))
          ua ! wrapAsPackedMsgParam(msg)
          val pm = expectMsgType[PackedMsg]
          handleGetConfigsResp(pm)
        }
      }

      "when sent REMOVE_CONFIGS msg with unsupported version" - {
        "should respond with unsupported version error msg" in {
          val msg = prepareRemoveConfigsForAgentWithVersion(unsupportedVersion, Set(""))
          ua ! wrapAsPackedMsgParam(msg)
          expectError(UNSUPPORTED_MSG_TYPE.statusCode)
        }
      }

      "when sent REMOVE_CONFIGS msg" - {
        "should respond with CONFIGS_REMOVED msg" in {
          val msg = prepareRemoveConfigsForAgent(Set(""))
          ua ! wrapAsPackedMsgParam(msg)
          val pm = expectMsgType[PackedMsg]
          handleConfigsRemovedResp(pm)
        }
      }

      "when sent UPDATE_CONFIGS msg with unsupported version" - {
        "should respond with unsupported version error msg" in {
          val updateTestConf = TestConfigDetail("verity.msgs.conn-req-expiration-time-in-seconds", Option("600"))
          val msg = prepareUpdateConfigsForAgentWithVersion(unsupportedVersion, Set(updateTestConf))
          ua ! wrapAsPackedMsgParam(msg)
          expectError(UNSUPPORTED_MSG_TYPE.statusCode)
        }
      }

      "when sent UPDATE_CONFIGS msg" - {
        "should respond with CONFIGS_UPDATED msg" in {
          val updateTestConf = TestConfigDetail("verity.msgs.conn-req-expiration-time-in-seconds", Option("600"))
          val msg = prepareUpdateConfigsForAgent(Set(updateTestConf))
          ua ! wrapAsPackedMsgParam(msg)
          val pm = expectMsgType[PackedMsg]
          handleConfigsUpdatedResp(pm)
        }
      }

      "when sent CREATE_KEY msg with unsupported version" - {
        "should respond with unsupported version error msg" in {
          val msg = prepareCreateKeyMsgForAgent(unsupportedVersion, connId2)
          ua ! wrapAsPackedMsgParam(msg)
          expectError(UNSUPPORTED_MSG_TYPE.statusCode) //TODO: message version not supported is not checked
        }
      }

      "when sent CREATE_KEY msg" - {
        "should respond with KEY_CREATED msg" in {
          val msg = prepareCreateKeyMsgForAgent(connId1)
          ua ! wrapAsPackedMsgParam(msg)
          val pm = expectMsgType[PackedMsg]
          handleKeyCreatedResp(pm, buildConnIdMap(connId1))
        }
      }
      updateComMethodSpecs()
    }
  }
  lazy val ecp: ExecutionContextProvider = new ExecutionContextProvider(appConfig)
  /**
   * custom thread pool executor
   */
  override def futureExecutionContext: ExecutionContext = ecp.futureExecutionContext

  override def executionContextProvider: ExecutionContextProvider = ecp
}
