package com.evernym.verity.actor.agent.agency.agent_provisioning

import com.evernym.verity.Base64Encoded
import com.evernym.verity.actor.agent.agency.GetLocalAgencyIdentity
import com.evernym.verity.actor.agent.msghandler.incoming.PackedMsgParam
import com.evernym.verity.actor.testkit.checks.{UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog}
import com.evernym.verity.actor.{AgencyPublicDid, agentRegion}
import com.evernym.verity.agentmsg.msgpacker.PackedMsg
import com.evernym.verity.protocol.engine.{DID, VerKey}
import com.evernym.verity.protocol.protocols.agentprovisioning.v_0_7.AgentProvisioningMsgFamily.{ProvisionToken, RequesterKeys}
import com.evernym.verity.testkit.mock.edge_agent.MockEdgeAgent
import com.evernym.verity.util.TimeUtil.IsoDateTime
import com.evernym.verity.util.{Base64Util, TimeUtil}
import com.evernym.verity.vault._

import scala.concurrent.duration.Duration

trait AgencyAgentPairwiseSpec_V_0_7 extends AgencyAgentPairwiseSpecBase {

  lazy val aap = agentRegion(agencyAgentPairwiseEntityId, agencyAgentPairwiseRegion)

  override def beforeAll(): Unit = {
    super.beforeAll()
    setupAgency()
  }

  lazy val AGENCY_PAIRWISE_AGENT_DID: DID = mockEdgeAgent.agencyPairwiseAgentDetailReq.DID
  lazy val AGENT_VK: VerKey = mockEdgeAgent.getVerKeyFromWallet(AGENCY_PAIRWISE_AGENT_DID)
  lazy val SPONSOR_KEYS: NewKeyCreated = walletAPI.createNewKey(CreateNewKeyParam(seed=Some("000000000000000000000000Trustee1")))
  val SPONSOR_ID = "evernym-test-sponsorabc123"
  val INACTIVE_SPONSOR_ID = "inactive-sponsor-id"
  val DID: DID = mockEdgeAgent.myDIDDetail.did
  val VK: VerKey = mockEdgeAgent.getVerKeyFromWallet(DID)
  val REQUESTER_KEYS: RequesterKeys = RequesterKeys(DID, VK)
  val NONCE = "123"
  val ID = "myId"
  val TIME_STAMP: IsoDateTime = TimeUtil.nowDateString
  def sig(nonce: String=NONCE, timestamp: String=TIME_STAMP, id: String=ID, sponsorId: String=SPONSOR_ID, vk: VerKey=AGENT_VK): Base64Encoded = {
    val encrypted = walletAPI.signMsg {
      SignMsgParam(KeyInfo(
        Left(vk)),
        (nonce + timestamp + id + sponsorId).getBytes()
      )
    }
    Base64Util.getBase64Encoded(encrypted)
  }

  def agencyAgentPairwiseSetup(edgeAgent: MockEdgeAgent=mockEdgeAgent, name: String="mockEdgeAgent"): Unit = {
    var pairwiseDID: DID = null

    s"when sent GetLocalAgencyDIDDetail command ($name)" - {
      "should respond with agency DID detail" taggedAs (UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog) in {
        aa ! GetLocalAgencyIdentity()
        val dd = expectMsgType[AgencyPublicDid]
        edgeAgent.handleFetchAgencyKey(dd)
      }
    }

    s"when sent CREATE_KEY msg ($name)" - {
      "should respond with KEY_CREATED msg" taggedAs (UNSAFE_IgnoreLog)  in {
        val msg = edgeAgent.v_0_6_req.prepareConnectCreateKey(
          edgeAgent.myDIDDetail.did, edgeAgent.myDIDDetail.verKey, edgeAgent.agencyAgentDetailReq.DID
        )
        aa ! PackedMsgParam(msg, reqMsgContext)
        val pm = expectMsgType[PackedMsg]
        val resp = edgeAgent.v_0_6_resp.handleConnectKeyCreatedResp(pm)
        pairwiseDID = resp.withPairwiseDID
      }
    }

    s"when sent get route to routing agent ($name)" - {
      "should be able to get persistence id of newly created pairwise actor" in {
        setPairwiseEntityId(pairwiseDID)
      }
    }
  }
}

class AgencyAgentCreateNewAgentFailure extends AgencyAgentPairwiseSpec_V_0_7 {
  import mockEdgeAgent.v_0_7_req._
  import mockEdgeAgent.v_0_7_resp._

  def createAgentFailures(): Unit = {
    "when sent create agent msg where sponsor is inactive" - {
      "should send problem report" taggedAs (UNSAFE_IgnoreLog) in {
        val requesterDetails = Some(ProvisionToken(ID, INACTIVE_SPONSOR_ID, NONCE, TIME_STAMP, sig(), SPONSOR_KEYS.verKey))
        val msg = prepareCreateAgentMsg(
          AGENCY_PAIRWISE_AGENT_DID, REQUESTER_KEYS, requesterDetails
        )
        aap ! PackedMsgParam(msg, reqMsgContext)
        val pm = expectMsgType[PackedMsg]
        handleCreateAgentProblemReport(pm)
      }
    }

    "when sent create agent msg before connecting where token is needed" - {
      "should send problem report" taggedAs (UNSAFE_IgnoreLog) in {
        val msg = prepareCreateAgentMsg(
          AGENCY_PAIRWISE_AGENT_DID, REQUESTER_KEYS, None
        )
        aap ! PackedMsgParam(msg, reqMsgContext)
        val pm = expectMsgType[PackedMsg]
        handleCreateAgentProblemReport(pm)
      }
    }

    "when sent create agent msg before connecting where sponsor is not found" - {
      "should send problem report" taggedAs (UNSAFE_IgnoreLog) in {
        val requesterDetails = Some(ProvisionToken(ID, "Not Found", NONCE, TIME_STAMP, sig(), SPONSOR_KEYS.verKey))
        val msg = prepareCreateAgentMsg(
          AGENCY_PAIRWISE_AGENT_DID, REQUESTER_KEYS, requesterDetails
        )
        aap ! PackedMsgParam(msg, reqMsgContext)
        val pm = expectMsgType[PackedMsg]
        handleCreateAgentProblemReport(pm)
      }
    }

    "when sent create agent msg with timeout" - {
      "should send problem report" taggedAs (UNSAFE_IgnoreLog) in {
        val expired = TimeUtil.longToDateString(Duration("12 minute").toMillis + TimeUtil.now)
        val checkNow = TimeUtil.nowDateString
        println(expired)
        println(checkNow)
        val requesterDetails = Some(ProvisionToken(ID, SPONSOR_ID, NONCE, expired, sig(timestamp=expired,vk=SPONSOR_KEYS.verKey), SPONSOR_KEYS.verKey))
        val msg = prepareCreateAgentMsg(
          AGENCY_PAIRWISE_AGENT_DID, REQUESTER_KEYS, requesterDetails
        )
        aap ! PackedMsgParam(msg, reqMsgContext)
        val pm = expectMsgType[PackedMsg]
        handleCreateAgentProblemReport(pm)
      }
    }

  }
  agencyAgentPairwiseSetup()
  createAgentFailures()
}

class AgencyAgentCreateNewCloudAgent extends AgencyAgentPairwiseSpec_V_0_7 {

  import mockEdgeAgent.v_0_7_req._
  import mockEdgeAgent.v_0_7_resp._

  def createCloudAgent(): Unit = {
    "when sent first create agent (cloud) msg" - {
      "should respond with AGENT_CREATED msg" in {
        val requesterDetails = Some(ProvisionToken(ID, SPONSOR_ID, NONCE, TIME_STAMP, sig(vk=SPONSOR_KEYS.verKey), SPONSOR_KEYS.verKey))
        val msg = prepareCreateAgentMsg(
          AGENCY_PAIRWISE_AGENT_DID, REQUESTER_KEYS, requesterDetails
        )
        aap ! PackedMsgParam(msg, reqMsgContext)
        val pm = expectMsgType[PackedMsg]
        val agentCreated = handleAgentCreatedResp(pm)
        agentCreated.selfDID shouldBe REQUESTER_KEYS.fromDID
      }
    }

    "when sent create agent msg after connecting" - {
      "should respond with error agent already created" taggedAs (UNSAFE_IgnoreLog) in {
        val requesterDetails = Some(ProvisionToken(ID, SPONSOR_ID, NONCE, TIME_STAMP, sig(vk=SPONSOR_KEYS.verKey), SPONSOR_KEYS.verKey))
        val msg = prepareCreateAgentMsg(
          AGENCY_PAIRWISE_AGENT_DID, REQUESTER_KEYS, requesterDetails
        )
        aap ! PackedMsgParam(msg, reqMsgContext)
        val pm = expectMsgType[PackedMsg]
        handleCreateAgentProblemReport(pm)
      }
    }
  }
  agencyAgentPairwiseSetup()
  createCloudAgent()
  "when tried to restart actor" - {
    "should be successful and respond" taggedAs (UNSAFE_IgnoreAkkaEvents) in {
      restartSpecs(aap)
    }
  }
}

class AgencyAgentCreateNewEdgeAgent extends AgencyAgentPairwiseSpec_V_0_7 {

  import mockEdgeAgent.v_0_7_req._
  import mockEdgeAgent.v_0_7_resp._

  def createEdgeAgent(): Unit = {
    "when sent first create agent (edge) msg 0.7" - {
      "should respond with AGENT_CREATED msg" taggedAs (UNSAFE_IgnoreLog)  in {
        val requesterDetails = Some(ProvisionToken(ID, SPONSOR_ID, NONCE, TIME_STAMP, sig(vk=SPONSOR_KEYS.verKey), SPONSOR_KEYS.verKey))
        val msg = prepareCreateEdgeAgentMsg(
          AGENCY_PAIRWISE_AGENT_DID, VK, requesterDetails
        )
        aap ! PackedMsgParam(msg, reqMsgContext)
        val pm = expectMsgType[PackedMsg]
        val agentCreated = handleAgentCreatedResp(pm)
        agentCreated.selfDID should not be REQUESTER_KEYS.fromDID
      }
    }

    "when sent create agent msg after connecting" - {
      "should respond with error agent already created" taggedAs (UNSAFE_IgnoreLog) in {
        val requesterDetails = Some(ProvisionToken(ID, SPONSOR_ID, NONCE, TIME_STAMP, sig(vk=SPONSOR_KEYS.verKey), SPONSOR_KEYS.verKey))
        val msg = prepareCreateAgentMsg(
          AGENCY_PAIRWISE_AGENT_DID, REQUESTER_KEYS, requesterDetails
        )
        aap ! PackedMsgParam(msg, reqMsgContext)
        val pm = expectMsgType[PackedMsg]
        handleCreateAgentProblemReport(pm)
      }
    }
  }
  agencyAgentPairwiseSetup()
  createEdgeAgent()

}

class AgencyAgentCreateNewAgentTokenDoubleUseFailure extends AgencyAgentPairwiseSpec_V_0_7 {
//  import mockEdgeAgent.v_0_7_req._
//  import mockEdgeAgent.v_0_7_resp._

  lazy val mockEdgeAgent1: MockEdgeAgent = buildMockConsumerEdgeAgent(platform.agentActorContext.appConfig, mockAgencyAdmin)
  lazy val mockEdgeAgent2: MockEdgeAgent = buildMockConsumerEdgeAgent(platform.agentActorContext.appConfig, mockAgencyAdmin)

  def createFirstAgent(): Unit = {
    "when sent first create agent (cloud) msg" - {
      "should respond with AGENT_CREATED msg" in {
        val aap = agentRegion(agencyAgentPairwiseEntityId, agencyAgentPairwiseRegion)
        println(s"val aap = agentRegion($agencyAgentPairwiseEntityId, $agencyAgentPairwiseRegion)")
        val requesterDetails = Some(ProvisionToken(ID, SPONSOR_ID, NONCE, TIME_STAMP, sig(vk=SPONSOR_KEYS.verKey), SPONSOR_KEYS.verKey))
        val requesterKeys = RequesterKeys(mockEdgeAgent1.myDIDDetail.did, mockEdgeAgent1.myDIDDetail.verKey)

        val msg = mockEdgeAgent1.v_0_7_req.prepareCreateAgentMsg(
          mockEdgeAgent1.agencyPairwiseAgentDetailReq.DID, requesterKeys, requesterDetails
        )
        aap ! PackedMsgParam(msg, reqMsgContext)
        val pm = expectMsgType[PackedMsg]
        val agentCreated = mockEdgeAgent1.v_0_7_resp.handleAgentCreatedResp(pm)
        agentCreated.selfDID shouldBe requesterKeys.fromDID
      }
    }
  }

  def createSecondAgent(): Unit = {
    "when sent second create agent (cloud) msg" - {
      "should fail with problem report msg" taggedAs (UNSAFE_IgnoreLog) in {
        val aap = agentRegion(agencyAgentPairwiseEntityId, agencyAgentPairwiseRegion)
        println(s"val aap = agentRegion($agencyAgentPairwiseEntityId, $agencyAgentPairwiseRegion)")
        val requesterDetails = Some(ProvisionToken(ID, SPONSOR_ID, NONCE, TIME_STAMP, sig(vk=SPONSOR_KEYS.verKey), SPONSOR_KEYS.verKey))
        val requesterKeys = RequesterKeys(mockEdgeAgent2.myDIDDetail.did, mockEdgeAgent2.myDIDDetail.verKey)

        val msg = mockEdgeAgent2.v_0_7_req.prepareCreateAgentMsg(
          mockEdgeAgent2.agencyPairwiseAgentDetailReq.DID, RequesterKeys(DID, VK), requesterDetails
        )
        aap ! PackedMsgParam(msg, reqMsgContext)
        val pm = expectMsgType[PackedMsg]
        mockEdgeAgent2.v_0_7_resp.handleCreateAgentProblemReport(pm)
      }
    }
  }

  agencyAgentPairwiseSetup(mockEdgeAgent1, "mockEdgeAgent1")
  createFirstAgent()
  agencyAgentPairwiseSetup(mockEdgeAgent2, "mockEdgeAgent2")
  createSecondAgent()
}