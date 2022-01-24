package com.evernym.verity.actor.agent.agency.agent_provisioning

import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.actor.agent.{AgentProvHelper, SponsorRel}
import com.evernym.verity.actor.testkit.checks.{UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog}
import com.evernym.verity.testkit.mock.agent.MockEnvUtil._
import com.evernym.verity.util.TimeUtil.IsoDateTime
import com.evernym.verity.util.TimeUtil
import com.evernym.verity.testkit.HasTestWalletAPI
import com.evernym.verity.testkit.mock.agent.MockEdgeAgent

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration

trait AgentProvBaseSpec_V_0_7
  extends AgentProvHelper
  with HasTestWalletAPI

class AgencyAgentCreateNewAgentFailure extends AgentProvBaseSpec_V_0_7 {
  import mockEdgeAgent.v_0_7_resp._

  def createAgentFailures(): Unit = {
    "when sent create agent msg where sponsor is inactive" - {
      "should send problem report" taggedAs UNSAFE_IgnoreLog in {

        val sentCreateAgent = sendCreateCloudAgent(
          SponsorRel("inactive", "whatever"),
          testSponsor.verKey,
          getNonce,
          mockEdgeAgent,
          TimeUtil.nowDateString
        )
        handleCreateAgentProblemReport(sentCreateAgent.msg)
      }
    }

    "when sent create agent msg where token is needed" - {
      "should send problem report" taggedAs UNSAFE_IgnoreLog in {
        val sentCreateAgent = sendCreateCloudAgent(
          SponsorRel.empty,
          testSponsor.verKey,
          getNonce,
          mockEdgeAgent,
          TimeUtil.nowDateString
        )
        handleCreateAgentProblemReport(sentCreateAgent.msg)
      }
    }

    "when sent create agent msg where sponsor is not found" - {
      "should send problem report" taggedAs UNSAFE_IgnoreLog in {
        val sentCreateAgent = sendCreateCloudAgent(
          SponsorRel("not found", "whatever"),
          testSponsor.verKey,
          getNonce,
          mockEdgeAgent,
          TimeUtil.nowDateString
        )
        handleCreateAgentProblemReport(sentCreateAgent.msg)
      }
    }

    "when sent create agent msg with timeout" - {
      "should send problem report" taggedAs UNSAFE_IgnoreLog in {
        val sentCreateAgent = sendCreateCloudAgent(
          SponsorRel("inactive", "whatever"),
          testSponsor.verKey,
          getNonce,
          mockEdgeAgent,
          TimeUtil.longToDateString(Duration("12 minute").toMillis + TimeUtil.now)
        )
        handleCreateAgentProblemReport(sentCreateAgent.msg)
      }
    }

  }
  createAgentFailures()
  lazy val ecp: ExecutionContextProvider = new ExecutionContextProvider(appConfig)
  override def futureExecutionContext: ExecutionContext = ecp.futureExecutionContext
  override def executionContextProvider: ExecutionContextProvider = ecp
}

class AgencyAgentCreateNewCloudAgent extends AgentProvBaseSpec_V_0_7 {

  import mockEdgeAgent.v_0_7_resp._

  def createCloudAgentTest(): Unit = {
    "when sent first create agent (cloud) msg" - {
      "should respond with AGENT_CREATED msg" in {

        val agentDid = createCloudAgent(SponsorRel("sponsor1", "id"),
          testSponsor.verKey,
          getNonce,
          mockEdgeAgent)
        agentDid shouldBe mockEdgeAgent.myDIDDetail.did
      }
    }

    "when resent same create agent message" - {
      "should respond with error agent already created" taggedAs UNSAFE_IgnoreLog in {
        val sentCreateAgent = sendCreateCloudAgent(
          SponsorRel("sponsor1", "id"),
          testSponsor.verKey,
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
    "should be successful and respond" taggedAs UNSAFE_IgnoreAkkaEvents in {
      restartPersistentActor(aa)
    }
  }

  lazy val ecp: ExecutionContextProvider = new ExecutionContextProvider(appConfig)
  override def futureExecutionContext: ExecutionContext = ecp.futureExecutionContext
  override def executionContextProvider: ExecutionContextProvider = ecp
}

class AgencyAgentCreateNewEdgeAgent extends AgentProvBaseSpec_V_0_7 {

  import mockEdgeAgent.v_0_7_resp._

  lazy val nonce: String = getNonce

  def createEdgeAgentTest(): Unit = {
    "when sent first create edge agent msg 0.7" - {
      "should respond with AGENT_CREATED msg with new domainId" taggedAs UNSAFE_IgnoreLog  in {

        val agentDid = createEdgeAgent(
          SponsorRel("sponsor1", "id"),
          testSponsor.verKey,
          nonce,
          mockEdgeAgent,
          TimeUtil.nowDateString
        )
        agentDid should not be mockEdgeAgent.myDIDDetail.did
      }
    }

    "when sent same create agent with same used token" - {
      "should respond successfully" taggedAs UNSAFE_IgnoreLog in {
        val sentCreateAgent = sendCreateEdgeAgent(
          SponsorRel("sponsor1", "id"),
          testSponsor.verKey,
          nonce,
          mockEdgeAgent,
          TimeUtil.nowDateString
        )
        handleAgentCreatedResp(sentCreateAgent.msg)
      }
    }
  }

  createEdgeAgentTest()

  lazy val ecp: ExecutionContextProvider = new ExecutionContextProvider(appConfig)

  override def futureExecutionContext: ExecutionContext = ecp.futureExecutionContext
  override def executionContextProvider: ExecutionContextProvider = ecp
}

class AgencyAgentCreateNewAgentTokenDoubleUseFailure extends AgentProvBaseSpec_V_0_7 {
  import mockEdgeAgent.v_0_7_resp._

  lazy val mockEdgeAgent1: MockEdgeAgent = buildMockEdgeAgent(mockAgencyAdmin, futureExecutionContext)

  lazy val nonce: String = getNonce
  lazy val commonTime: IsoDateTime = TimeUtil.nowDateString

  def createFirstAgent(): Unit = {
    "when sent first create agent (cloud) msg" - {
      "should respond with AGENT_CREATED msg" in {
        val selfDID = createCloudAgent(
          SponsorRel("sponsor1", "id"),
          testSponsor.verKey,
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
      "should fail with problem report msg" taggedAs UNSAFE_IgnoreLog in {

        val sentCreateAgent = sendCreateEdgeAgent(
          SponsorRel("sponsor1", "id"),
          testSponsor.verKey,
          nonce,
          mockEdgeAgent,
          commonTime
        )
        handleCreateAgentProblemReport(sentCreateAgent.msg)
      }
    }
  }

  createFirstAgent()
  createSecondAgent()

  lazy val ecp: ExecutionContextProvider = new ExecutionContextProvider(appConfig)
  /**
   * custom thread pool executor
   */
  override def futureExecutionContext: ExecutionContext = ecp.futureExecutionContext

  override def executionContextProvider: ExecutionContextProvider = ecp
}