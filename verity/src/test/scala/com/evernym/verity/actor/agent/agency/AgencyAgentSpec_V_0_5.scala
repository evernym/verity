package com.evernym.verity.actor.agent.agency

import com.evernym.verity.Status.{CONN_STATUS_ALREADY_CONNECTED, UNSUPPORTED_MSG_TYPE}
import com.evernym.verity.actor.agent.msghandler.incoming.PackedMsgParam
import com.evernym.verity.actor.{AgencyPublicDid, agentRegion}
import com.evernym.verity.util.PackedMsgWrapper
import com.evernym.verity.actor.wallet.PackedMsg

class AgencyAgentSpec_V_0_5 extends AgencyAgentScaffolding {

  import mockEdgeAgent.v_0_5_req._
  import mockEdgeAgent.v_0_5_resp._

  agencyAgentSpec()

  def agencyAgentSpec(): Unit = {
    agencySetupSpecs()
    connectingSpecs()
    connectingSpecWithDifferentEdgeAgent()
    restartSpecs()
  }

  private def connectingSpecs(): Unit = {

    "AgencyAgent" - {

      //fixture for common agency agent used across tests in this scope
      lazy val aa = agentRegion(agencyAgentEntityId, agencyAgentRegion)

      "when edge agent 0.5 is interacting" - {

        "when sent GetLocalAgencyDIDDetail command" - {
          "should respond with agency DID detail" in {
            aa ! GetLocalAgencyIdentity()
            expectMsgType[AgencyPublicDid]
          }
        }

        "when sent an unsupported msg" - {
          "should respond with unsupported msg type error msg" in {
            aa ! None
            expectError(UNSUPPORTED_MSG_TYPE.statusCode)
          }
        }

        "when sent bundled message with only unsupported SIGNUP msg" - {
          "should respond with unsupported msg type error msg" in {
            val msg = prepareUnsupportedMsgForAgencyWithVersion(unsupportedVersion)
            aa ! PackedMsgParam(msg, reqMsgContext)
            expectError(UNSUPPORTED_MSG_TYPE.statusCode)
          }
        }

        "when sent bundled message which has an unsupported SIGNUP msg" - {
          "should respond with unsupported msg type error msg" in {
            val msg = prepareUnsupportedMsgWithMoreThanOneMsgsForAgency(unsupportedVersion)
            aa ! PackedMsgParam(msg, reqMsgContext)
            expectError(UNSUPPORTED_MSG_TYPE.statusCode)
          }
        }

        "when sent FWD msg with unsupported version msg" - {
          "should respond with unsupported version error msg" in {
            val msg = prepareConnectMsgForAgency(unsupportedVersion).msg
            aa ! PackedMsgWrapper(msg, reqMsgContext)
            expectError(UNSUPPORTED_MSG_TYPE.statusCode)
          }
        }

        "when sent CONNECT msg 0.5" - {
          "should respond with CONNECTED msg" in {
            val msg = prepareConnectMsg()
            aa ! PackedMsgParam(msg, reqMsgContext)
            val pm = expectMsgType[PackedMsg]
            handleConnectedResp(pm)
          }
        }

        "when resent CONNECT msg" - {
          "should respond with already connected error msg" in {
            val msg = prepareConnectMsg()
            aa ! PackedMsgParam(msg, reqMsgContext)
            expectError(CONN_STATUS_ALREADY_CONNECTED.statusCode)
          }
        }
      }
    }
  }

  private def connectingSpecWithDifferentEdgeAgent(): Unit = {

    "when sent GetLocalAgencyDIDDetail command" - {
      "should respond with agency DID detail" in {
        aa ! GetLocalAgencyIdentity()
        val dd = expectMsgType[AgencyPublicDid]
        mockEdgeAgent1.handleFetchAgencyKey(dd)
      }
    }

    "when resent CONNECT msg with different content" - {
      "should respond with CONNECTED msg" in {
        val msg = mockEdgeAgent1.v_0_5_req.prepareConnectMsg()
        aa ! PackedMsgParam(msg, reqMsgContext)
        val pm = expectMsgType[PackedMsg]
        mockEdgeAgent1.v_0_5_resp.handleConnectedResp(pm)
      }
    }
  }
}

