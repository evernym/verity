package com.evernym.verity.actor.agent.agency

import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.actor.AgencyPublicDid
import com.evernym.verity.actor.agent.msghandler.incoming.ProcessPackedMsg
import com.evernym.verity.actor.wallet.PackedMsg

import scala.concurrent.ExecutionContext
class AgencyAgentSpec_V_0_6 extends AgencyAgentScaffolding {

  agencyAgentSpec()

  def agencyAgentSpec(): Unit = {
    agencySetupSpecs()
    agentProvisioningSpecs()
    connectingSpecs()
    restartSpecs()
  }

  private def connectingSpecs(): Unit = {

    "when sent GetLocalAgencyDIDDetail command for mock edge agent 1" - {
      "should respond with agency DID detail" in {
        aa ! GetAgencyAgentDetail
        val ad = expectMsgType[AgencyAgentDetail]
        ad.didPair.validate()
        ad.walletId.nonEmpty shouldBe true

        aa ! GetLocalAgencyIdentity()
        val apd = expectMsgType[AgencyPublicDid]
        apd.DID shouldBe ad.did
        apd.verKey shouldBe ad.verKey

        aa ! GetAgencyIdentity(apd.DID)
        val ai = expectMsgType[AgencyInfo]
        ai.verKeyReq shouldBe ad.verKey

        mockEdgeAgent1.handleFetchAgencyKey(apd)
      }
    }

    "when sent CREATE_KEY msg" - {
      "should respond with KEY_CREATED msg" in {
        val fromDID = mockEdgeAgent1.myDIDDetail.did
        val fromDIDVerKey = mockEdgeAgent1.getVerKeyFromWallet(fromDID)
        val msg = mockEdgeAgent1.v_0_6_req.prepareConnectCreateKey(fromDID, fromDIDVerKey, mockEdgeAgent1.agencyAgentDetailReq.DID)
        aa ! ProcessPackedMsg(msg, reqMsgContext)
        val pm = expectMsgType[PackedMsg]
        mockEdgeAgent1.v_0_6_resp.handleConnectKeyCreatedResp(pm)
      }
    }
  }

  private def agentProvisioningSpecs(): Unit = {

    "when sent GetLocalAgencyDIDDetail command" - {
      "should respond with agency DID detail" in {
        aa ! GetLocalAgencyIdentity()
        expectMsgType[AgencyPublicDid]
      }
    }

    "when sent create agent msg" - {
      "should respond with AGENT_CREATED msg" in {
        val fromDID = mockEdgeAgent.myDIDDetail.did
        val fromDIDVerKey = mockEdgeAgent.getVerKeyFromWallet(fromDID)
        val msg = mockEdgeAgent.v_0_6_req.prepareCreateAgentMsg(
          mockEdgeAgent.agencyAgentDetailReq.DID, fromDID, fromDIDVerKey)
        aa ! ProcessPackedMsg(msg, reqMsgContext)
        expectMsgType[PackedMsg]
      }
    }
  }

  lazy val ecp: ExecutionContextProvider = new ExecutionContextProvider(appConfig)
  /**
   * custom thread pool executor
   */
  override def futureExecutionContext: ExecutionContext = ecp.futureExecutionContext

  override def executionContextProvider: ExecutionContextProvider = ecp

  override def futureWalletExecutionContext: ExecutionContext = ecp.walletFutureExecutionContext
}
