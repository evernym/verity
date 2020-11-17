package com.evernym.integrationtests.e2e.env

import com.evernym.verity.protocol.engine.Constants.MTV_1_0
import com.evernym.verity.testkit.agentmsg.{AgentMsgPackagingContext, AgentMsgSenderHttpWrapper, GeneralMsgCreatedResp_MFV_0_5}
import com.evernym.verity.testkit.mock.edge_agent.{MockConsumerEdgeAgent, MockEntEdgeAgent}
import com.evernym.verity.UrlDetail
import com.evernym.verity.actor.agent.MsgPackFormat.MPF_MSG_PACK
import com.evernym.verity.testkit.mock.cloud_agent.MockCloudAgent

class MockAgentService(val urlDetail: UrlDetail)
  extends AgentMsgSenderHttpWrapper {
  override val mockClientAgent = new MockCloudAgent(urlDetail, appConfig)
}

object Executor {

  class MockEnterpriseEdgeAgentApiExecutor(val urlDetail: UrlDetail=UrlDetail("localhost:9002"))
    extends AgentMsgSenderHttpWrapper {
    override val mockClientAgent = new MockEntEdgeAgent(urlDetail, appConfig)
  }

  class MockConsumerEdgeAgentApiExecutor(val urlDetail: UrlDetail=UrlDetail("localhost:9001"))
    extends AgentMsgSenderHttpWrapper {
    override val mockClientAgent = new MockConsumerEdgeAgent(urlDetail, appConfig)
  }

  class MockVerityEdgeAgentApiExecutor(val urlDetail: UrlDetail=UrlDetail("localhost:9003"))
    extends AgentMsgSenderHttpWrapper {
    override val mockClientAgent = new MockEntEdgeAgent(urlDetail, appConfig)
  }

  class MockThirdPartyEdgeAgentApiExecutor(val urlDetail: UrlDetail=UrlDetail("localhost:9004"))
    extends AgentMsgSenderHttpWrapper {
    override val mockClientAgent = new MockEntEdgeAgent(urlDetail, appConfig)
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