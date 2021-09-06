package com.evernym.verity.actor.agent.agency.agent_provisioning

import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.util2.Status._
import com.evernym.verity.actor.agent.agency.GetLocalAgencyIdentity
import com.evernym.verity.actor.agent.msghandler.incoming.ProcessPackedMsg
import com.evernym.verity.actor.testkit.checks.{UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog}
import com.evernym.verity.actor.{AgencyPublicDid, agentRegion}
import com.evernym.verity.did.DidStr
import com.evernym.verity.actor.wallet.PackedMsg
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Seconds, Span}

import scala.concurrent.ExecutionContext


class AgencyAgentPairwiseSpec_V_0_6 extends AgencyAgentPairwiseSpecBase with Eventually {

  import mockEdgeAgent.v_0_6_req._
  import mockEdgeAgent.v_0_6_resp._

  lazy val aap = agentRegion(agencyAgentPairwiseEntityId, agencyAgentPairwiseRegion)

  override def beforeAll(): Unit = {
    super.beforeAll()
    setupAgency()
  }

  agentProvisioningSpecs()

  def agentProvisioningSpecs(): Unit = {

    "Agent provisioning 0.6" - {

      var pairwiseDID: DidStr = null

      "when sent GetLocalAgencyDIDDetail command" - {
        "should respond with agency DID detail" in {
          aa ! GetLocalAgencyIdentity()
          val dd = expectMsgType[AgencyPublicDid]
          mockEdgeAgent.handleFetchAgencyKey(dd)
        }
      }

      "when sent CREATE_KEY msg" - {
        "should respond with KEY_CREATED msg" in {
          val fromDID = mockEdgeAgent.myDIDDetail.did
          val fromDIDVerKey = mockEdgeAgent.getVerKeyFromWallet(fromDID)
          val msg = prepareConnectCreateKey(
            fromDID, fromDIDVerKey, mockEdgeAgent.agencyAgentDetailReq.DID)
          aa ! ProcessPackedMsg(msg, reqMsgContext)
          val pm = expectMsgType[PackedMsg]
          val resp = handleConnectKeyCreatedResp(pm)
          pairwiseDID = resp.withPairwiseDID
        }
      }

      "when sent get route to routing agent" - {
        "should be able to get persistence id of newly created pairwise actor" in {
          setPairwiseEntityId(pairwiseDID)
        }
      }

      "when sent create agent msg before connecting" - {
        "should respond with AGENT_CREATED msg" in {
          val fromDID = mockEdgeAgent.myDIDDetail.did
          val fromDIDVerKey = mockEdgeAgent.getVerKeyFromWallet(fromDID)
          val msg = prepareCreateAgentMsg(
            mockEdgeAgent.agencyPairwiseAgentDetailReq.did,
            fromDID, fromDIDVerKey)
          aap ! ProcessPackedMsg(msg, reqMsgContext)
          expectMsgType[PackedMsg]
        }
      }

      "when sent connection request msg" - {
        "should respond with connection request detail" in {
          val msg = prepareCreateInvite(mockEdgeAgent.agencyPairwiseAgentDetailReq.did, None)
          aap ! ProcessPackedMsg(msg, reqMsgContext)
          expectMsgType[PackedMsg]
        }
      }

      "when sent create agent msg after connecting" - {
        "should respond with error agent already created" taggedAs (UNSAFE_IgnoreAkkaEvents) in {
          eventually(timeout(Span(5, Seconds))) {
            val fromDID = mockEdgeAgent.myDIDDetail.did
            val fromDIDVerKey = mockEdgeAgent.getVerKeyFromWallet(fromDID)
            val msg = prepareCreateAgentMsg(
              mockEdgeAgent.agencyPairwiseAgentDetailReq.did,
              fromDID, fromDIDVerKey)
            aap ! ProcessPackedMsg(msg, reqMsgContext)
            expectError(AGENT_ALREADY_CREATED.statusCode)
          }
        }
      }

      "when tried to restart actor" - {
        "should be successful and respond" taggedAs(UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog) in {
          restartPersistentActor(aap)
        }
      }
    }
  }

  lazy val ecp: ExecutionContextProvider = new ExecutionContextProvider(appConfig)

  /**
   * custom thread pool executor
   */
  override def futureExecutionContext: ExecutionContext = ecp.futureExecutionContext

  override def executionContextProvider: ExecutionContextProvider = ecp
}
