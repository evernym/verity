package com.evernym.integrationtests.e2e.env

import com.evernym.integrationtests.e2e.util.TestExecutionContextProvider
import com.evernym.verity.protocol.engine.Constants.MTV_1_0
import com.evernym.verity.testkit.agentmsg.{AgentMsgPackagingContext, AgentMsgSenderHttpWrapper, GeneralMsgCreatedResp_MFV_0_5}
import com.evernym.verity.actor.agent.MsgPackFormat.MPF_MSG_PACK
import com.evernym.verity.testkit.mock.agent.{MockCloudAgent, MockEdgeAgent}
import com.evernym.verity.util2.{ExecutionContextProvider, UrlParam}

import scala.concurrent.ExecutionContext

class AgencyAgentSetupHelper {

  def setupAgencyAgent(url: String, seedOpt: Option[String]=None): Unit = {
    setupAgencyAgent(UrlParam(url), seedOpt)
  }
  def setupAgencyAgent(up: UrlParam, seedOpt: Option[String]): Unit = {
    val agencyAgent: AgentMsgSenderHttpWrapper = new AgentMsgSenderHttpWrapper {
      def urlParam: UrlParam = up
      override def futureExecutionContext: ExecutionContext = ecp.futureExecutionContext
      private val ecp = TestExecutionContextProvider.ecp
      override val mockClientAgent =
        new MockCloudAgent(
          ecp.futureExecutionContext,
          urlParam,
          appConfig,
          system = system
        )
    }
    agencyAgent.setupAgency(seedOpt orElse Option("0000000000000000000000000000" + up.port.toString.padTo(4, '0')))
  }

  def bootstrapAgencyAgentToLedger(str: String): Unit = {
    bootstrapAgencyAgentToLedger(UrlParam(str))
  }

  def bootstrapAgencyAgentToLedger(up: UrlParam): Unit = {
    val agencyAgent: AgentMsgSenderHttpWrapper = new AgentMsgSenderHttpWrapper {
      def urlParam: UrlParam = up
      override def futureExecutionContext: ExecutionContext = ecp.futureExecutionContext
      private val ecp = TestExecutionContextProvider.ecp
      override val mockClientAgent =
        new MockCloudAgent(
          ecp.futureExecutionContext,
          urlParam,
          appConfig,
          system = system
        )
    }
    agencyAgent.bootstrapAgencyAgentToLedger()
  }
}

object Executor {

  class MockEnterpriseEdgeAgentApiExecutor(val urlParam: UrlParam=UrlParam("localhost:6801"))
    extends AgentMsgSenderHttpWrapper {
    private val ecp = TestExecutionContextProvider.ecp
    override val mockClientAgent =
      new MockEdgeAgent(
        urlParam,
        appConfig,
        ecp.futureExecutionContext,
        system = system
      )
    override def futureExecutionContext: ExecutionContext = ecp.futureExecutionContext
  }

  class MockConsumerEdgeAgentApiExecutor(val urlParam: UrlParam=UrlParam("localhost:6701"))
    extends AgentMsgSenderHttpWrapper {
    private val ecp: ExecutionContextProvider = TestExecutionContextProvider.ecp
    override val mockClientAgent =
      new MockEdgeAgent(
        urlParam,
        appConfig,
        ecp.futureExecutionContext,
        system = system
      )
    override def futureExecutionContext: ExecutionContext = ecp.futureExecutionContext
  }

  class MockVerityEdgeAgentApiExecutor(val urlParam: UrlParam=UrlParam("localhost:6901"))
    extends AgentMsgSenderHttpWrapper {
    private val ecp = TestExecutionContextProvider.ecp
    override val mockClientAgent =
      new MockEdgeAgent(
        urlParam,
        appConfig,
        ecp.futureExecutionContext,
        system = system
      )
    override def futureExecutionContext: ExecutionContext = ecp.futureExecutionContext
  }

  class MockThirdPartyEdgeAgentApiExecutor(val urlParam: UrlParam=UrlParam("localhost:9004"))
    extends AgentMsgSenderHttpWrapper {
    private val ecp = TestExecutionContextProvider.ecp
    override val mockClientAgent =
      new MockEdgeAgent(
        urlParam,
        appConfig,
        ecp.futureExecutionContext,
        system = system
      )
    override def futureExecutionContext: ExecutionContext = ecp.futureExecutionContext
  }

  def prepareVerity1Apps(): EdgeApps = {
    val entSDK = new MockEnterpriseEdgeAgentApiExecutor()
    val userApp = new MockConsumerEdgeAgentApiExecutor()
    EdgeApps(entSDK, userApp)
  }

  def prepareVerity2Apps(): EdgeApps = {
    val entSDK = new MockVerityEdgeAgentApiExecutor()
    val userApp = new MockConsumerEdgeAgentApiExecutor()
    EdgeApps(entSDK, userApp)
  }

}

case class EdgeApps(entSDK: AgentMsgSenderHttpWrapper, userApp: AgentMsgSenderHttpWrapper) {

  def setupAgencyAgent(): EdgeApps = {
    entSDK.setupAgency(Option("0000000000000000000000000001LEAS"))
    userApp.setupAgency(Option("0000000000000000000000000001LCAS"))
    this
  }

  def provisionAgents(): EdgeApps = {
    entSDK.setupEAgent()
    userApp.setupCAgent()
    this
  }

  def connectAgents(connId: String): EdgeApps = {
    entSDK.sendInviteForConnExt(connId)
    userApp.answerInviteForConnExt(connId, entSDK.mockClientAgent)
    this
  }

  def performCredInteraction(connId: String): EdgeApps = {
    Thread.sleep(5000)
    val credOffer = entSDK.sendGeneralMsgToConn(connId, "credOffer", "cred-offer-msg").asInstanceOf[GeneralMsgCreatedResp_MFV_0_5]
    Thread.sleep(5000)
    val credReq = userApp.sendGeneralMsgToConn(connId, "credReq", "cred-req-msg", Option(credOffer.mc.uid)).asInstanceOf[GeneralMsgCreatedResp_MFV_0_5]
    Thread.sleep(5000)
    entSDK.sendGeneralMsgToConn(connId, "cred", "cred-msg", Option(credReq.mc.uid))
    this
  }

  def performProofInteraction(connId: String): EdgeApps = {
    Thread.sleep(5000)
    val proofReq = entSDK.sendGeneralMsgToConn(connId, "proofReq", "proof-req-msg").asInstanceOf[GeneralMsgCreatedResp_MFV_0_5]
    Thread.sleep(5000)
    userApp.sendGeneralMsgToConn(connId, "proof", "proof-msg", Option(proofReq.mc.uid)).asInstanceOf[GeneralMsgCreatedResp_MFV_0_5]
    this
  }

  def performCredAndProofInteraction(connId: String, times: Int = 1): EdgeApps = {
    (1 to times).foreach { _ =>
      Thread.sleep(2000)
      performCredInteraction(connId)
      performProofInteraction(connId)
    }
    this
  }

  def sendGetMsgs(): EdgeApps = {
    implicit val amc: AgentMsgPackagingContext = AgentMsgPackagingContext(MPF_MSG_PACK, MTV_1_0, packForAgencyRoute = true)
    entSDK.getMsgsFromConns_MPV_0_5()
    userApp.getMsgsFromConns_MPV_0_5()
    this
  }
}