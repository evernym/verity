package com.evernym.verity.actor.agent.agency.agent_provisioning

import com.evernym.verity.actor.agent.SponsorRel
import com.evernym.verity.actor.agent.agency.{GetLocalAgencyIdentity, UserAgentCreatorHelper}
import com.evernym.verity.actor.agent.msghandler.incoming.ProcessPackedMsg
import com.evernym.verity.actor.testkit.checks.{UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog}
import com.evernym.verity.actor.AgencyPublicDid
import com.evernym.verity.protocol.engine.DID
import com.evernym.verity.testkit.mock.edge_agent.MockEdgeAgent
import com.evernym.verity.util.TimeUtil.IsoDateTime
import com.evernym.verity.util.TimeUtil
import com.evernym.verity.actor.wallet.PackedMsg

import scala.concurrent.duration.Duration

trait AgencyAgentPairwiseSpec_V_0_7 extends AgencyAgentPairwiseSpecBase with UserAgentCreatorHelper {

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
      "should respond with KEY_CREATED msg" taggedAs UNSAFE_IgnoreLog  in {
        val msg = edgeAgent.v_0_6_req.prepareConnectCreateKey(
          edgeAgent.myDIDDetail.did, edgeAgent.myDIDDetail.verKey, edgeAgent.agencyAgentDetailReq.DID
        )
        aa ! ProcessPackedMsg(msg, reqMsgContext)
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
  import mockEdgeAgent.v_0_7_resp._

  def createAgentFailures(): Unit = {
    "when sent create agent msg where sponsor is inactive" - {
      "should send problem report" taggedAs (UNSAFE_IgnoreLog) in {
        val sentCreateAgent = sendCreateAgent(
          SponsorRel("inactive", "whatever"),
          sponsorKeys().verKey,
          getNonce,
          mockEdgeAgent,
          TimeUtil.nowDateString
        )
        handleCreateAgentProblemReport(sentCreateAgent.msg)
      }
    }

    "when sent create agent msg before connecting where token is needed" - {
      "should send problem report" taggedAs (UNSAFE_IgnoreLog) in {
        val sentCreateAgent = sendCreateAgent(
          SponsorRel.empty,
          sponsorKeys().verKey,
          getNonce,
          mockEdgeAgent,
          TimeUtil.nowDateString
        )
        handleCreateAgentProblemReport(sentCreateAgent.msg)
      }
    }

    "when sent create agent msg before connecting where sponsor is not found" - {
      "should send problem report" taggedAs (UNSAFE_IgnoreLog) in {
        val sentCreateAgent = sendCreateAgent(
          SponsorRel("not found", "whatever"),
          sponsorKeys().verKey,
          getNonce,
          mockEdgeAgent,
          TimeUtil.nowDateString
        )
        handleCreateAgentProblemReport(sentCreateAgent.msg)
      }
    }

    "when sent create agent msg with timeout" - {
      "should send problem report" taggedAs (UNSAFE_IgnoreLog) in {
        val sentCreateAgent = sendCreateAgent(
          SponsorRel("inactive", "whatever"),
          sponsorKeys().verKey,
          getNonce,
          mockEdgeAgent,
          TimeUtil.longToDateString(Duration("12 minute").toMillis + TimeUtil.now)
        )
        handleCreateAgentProblemReport(sentCreateAgent.msg)
      }
    }

  }
  createAgentFailures()
}

class AgencyAgentCreateNewCloudAgent extends AgencyAgentPairwiseSpec_V_0_7 {

  import mockEdgeAgent.v_0_7_resp._

  def createCloudAgentTest(): Unit = {
    "when sent first create agent (cloud) msg" - {
      "should respond with AGENT_CREATED msg" in {
        createCloudAgent(SponsorRel("sponsor1", "id"), sponsorKeys().verKey, getNonce)
      }
    }

    "when sent create agent msg after connecting" - {
      "should respond with error agent already created" taggedAs (UNSAFE_IgnoreLog) in {
        val sentCreateAgent = sendCreateAgent(
          SponsorRel("sponsor1", "id"),
          sponsorKeys().verKey,
          getNonce,
          mockEdgeAgent,
          TimeUtil.nowDateString
        )
        handleCreateAgentProblemReport(sentCreateAgent.msg)
      }
    }
  }
  createCloudAgentTest()
  "when tried to restart actor" - {
    "should be successful and respond" taggedAs (UNSAFE_IgnoreAkkaEvents) in {
      restartActor(aap)
    }
  }
}

class AgencyAgentCreateNewEdgeAgent extends AgencyAgentPairwiseSpec_V_0_7 {

  import mockEdgeAgent.v_0_7_resp._

  def createEdgeAgentTest(): Unit = {
    "when sent first create agent (edge) msg 0.7" - {
      "should respond with AGENT_CREATED msg with new domainId" taggedAs (UNSAFE_IgnoreLog)  in {
        val agent = newEdgeAgent()
        val agentDid = createCloudAgent(
          SponsorRel("sponsor1", "id"),
          sponsorKeys().verKey,
          getNonce,
          agent,
          TimeUtil.nowDateString,
          isEdgeAgent=true
        )
        agentDid should not be agent.myDIDDetail.did
      }
    }

    "when sent create agent msg after connecting" - {
      "should respond with error agent already created" taggedAs (UNSAFE_IgnoreLog) in {
        val sentCreateAgent = sendCreateAgent(
          SponsorRel("sponsor1", "id"),
          sponsorKeys().verKey,
          getNonce,
          mockEdgeAgent,
          TimeUtil.nowDateString,
          isEdgeAgent=true
        )
        handleCreateAgentProblemReport(sentCreateAgent.msg)
      }
    }
  }
  createEdgeAgentTest()
}

class AgencyAgentCreateNewAgentTokenDoubleUseFailure extends AgencyAgentPairwiseSpec_V_0_7 {
  import mockEdgeAgent.v_0_7_resp._

  lazy val mockEdgeAgent1: MockEdgeAgent = buildMockConsumerEdgeAgent(platform.agentActorContext.appConfig, mockAgencyAdmin)
  lazy val mockEdgeAgent2: MockEdgeAgent = buildMockConsumerEdgeAgent(platform.agentActorContext.appConfig, mockAgencyAdmin)
  lazy val nonce: String = getNonce
  lazy val commonTime: IsoDateTime = TimeUtil.nowDateString

  def createFirstAgent(): Unit = {
    "when sent first create agent (cloud) msg" - {
      "should respond with AGENT_CREATED msg" in {
        val selfDID = createCloudAgent(
          SponsorRel("sponsor1", "id"),
          sponsorKeys().verKey,
          nonce,
          mockEdgeAgent1,
          commonTime
        )
        selfDID shouldBe mockEdgeAgent1.myDIDDetail.did
      }
    }
  }

  def createSecondAgent(): Unit = {
    "when sent second create agent (cloud) msg" - {
      "should fail with problem report msg" taggedAs (UNSAFE_IgnoreLog) in {
        val sentCreateAgent = sendCreateAgent(
          SponsorRel("sponsor1", "id"),
          sponsorKeys().verKey,
          getNonce,
          mockEdgeAgent,
          commonTime,
          isEdgeAgent=true
        )
        handleCreateAgentProblemReport(sentCreateAgent.msg)
      }
    }
  }

  createFirstAgent()
  createSecondAgent()
}