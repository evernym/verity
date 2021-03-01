package com.evernym.verity.actor.agent.agency.agent_provisioning

import akka.actor.PoisonPill
import akka.cluster.sharding.ClusterSharding
import com.evernym.verity.Status._
import com.evernym.verity.Version
import com.evernym.verity.actor.agent.agency.GetLocalAgencyIdentity
import com.evernym.verity.actor.agent.msghandler.incoming.ProcessPackedMsg
import com.evernym.verity.actor.testkit.checks.UNSAFE_IgnoreAkkaEvents
import com.evernym.verity.actor.{AgencyPublicDid, ForIdentifier, agentRegion}
import com.evernym.verity.protocol.container.actor.ActorProtocol
import com.evernym.verity.protocol.engine.{DEFAULT_THREAD_ID, DID, PinstIdResolution}
import com.evernym.verity.protocol.protocols.agentprovisioning.v_0_5.AgentProvisioningProtoDef
import com.evernym.verity.actor.wallet.PackedMsg

class AgencyAgentPairwiseSpec_V_0_5 extends AgencyAgentPairwiseSpecBase {

  import mockEdgeAgent.v_0_5_req._
  import mockEdgeAgent.v_0_5_resp._

  override def beforeAll(): Unit = {
    super.beforeAll()
    setupAgency()
  }

  createAgencyAgentPairwiseActor()
  agencyAgentPairwiseSpecs()

  def createAgencyAgentPairwiseActor(): Unit = {

    mockEdgeAgent.getVerKeyFromWallet(mockEdgeAgent.myDIDDetail.did)

    var pairwiseDID: DID = null

    "User" - {

      "when sent GetLocalAgencyDIDDetail command" - {
        "should respond with agency DID detail" in {
          aa ! GetLocalAgencyIdentity()
          val dd = expectMsgType[AgencyPublicDid]
          mockEdgeAgent.handleFetchAgencyKey(dd)
        }
      }

      "when sent CONNECT msg" - {
        "should respond with CONNECTED msg" in {
          val msg = prepareConnectMsg()
          aa ! ProcessPackedMsg(msg, reqMsgContext)
          val pm = expectMsgType[PackedMsg]
          val connectedResp = handleConnectedResp(pm)
          pairwiseDID = connectedResp.withPairwiseDID
        }
      }

      "when sent get route to routing agent" - {
        "should be able to get persistence id of newly created pairwise actor" in {
          setPairwiseEntityId(pairwiseDID)
        }
      }
    }
  }

  def agencyAgentPairwiseSpecs(): Unit = {

    val unsupportedVersion: Version = "1.1"

    //fixture for common agency agent pairwise used across tests in this scope
    lazy val aap = agentRegion(agencyAgentPairwiseEntityId, agencyAgentPairwiseRegion)

    "AgencyPairwiseAgent (with 0.5) messages" - {

      "when sent SIGNUP msg with unsupported version" - {
        "should respond with unsupported version error msg" in {
          val msg = prepareSignUpMsgForVersion(unsupportedVersion)
          aap ! wrapAsPackedMsgParam(msg)
          expectError(UNSUPPORTED_MSG_TYPE.statusCode)
        }
      }

      "when sent SIGNUP msg with supported version" - {
        "should respond with SIGNED_UP msg" in {
          val msg = prepareSignUpMsg
          aap ! wrapAsPackedMsgParam(msg)
          val rm = expectMsgType[PackedMsg]
          handleSignedUpResp(rm)
        }
      }

      "when sent CREATE_AGENT msg with unsupported version" - {
        "should respond with unsupported version error msg" in {
          val msg = prepareCreateAgentMsg(unsupportedVersion)
          aap ! wrapAsPackedMsgParam(msg)
          expectError(UNSUPPORTED_MSG_TYPE.statusCode)
        }
      }

      "when sent PoisonPill to agent provisioning protocol container actor" - {
        "should not respond anything" taggedAs (UNSAFE_IgnoreAkkaEvents) in {
          //NOTE: there was an issue wherein actor based protocol container's driver was not being set
          // after the protocol container actor restart.
          //this test will stop the protocol container actor and test below should pass

          val pd = AgentProvisioningProtoDef
          val apap = new ActorProtocol(AgentProvisioningProtoDef)
          val apRegion = ClusterSharding.get(system).shardRegion(apap.typeName)

          //TODO: need to come back to this
          val selfId = mockAgencyAdmin.agencyPublicDid.get.DID
          val pinstId = PinstIdResolution.V0_2.resolve(
            pd,
            selfId,
            Option(agencyAgentPairwiseDID),
            Option(DEFAULT_THREAD_ID),
            None,
            None
          )

          apRegion ! ForIdentifier(pinstId, PoisonPill)
          expectNoMessage()
        }
      }

      "when sent CREATE_AGENT msg with supported version" - {
        "should respond with AGENT_CREATED msg" in {
          val msg = prepareCreateAgentMsg
          aap ! wrapAsPackedMsgParam(msg)
          val rm = expectMsgType[PackedMsg]
          handleAgentCreatedResp(rm)
        }
      }

      "when tried to restart actor" - {
        "should be successful and respond" taggedAs UNSAFE_IgnoreAkkaEvents in {
          restartPersistentActor(aap)
        }
      }
    }
  }
}

