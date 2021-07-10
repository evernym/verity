package com.evernym.verity.testkit.mock.agent

import akka.actor.ActorSystem
import com.evernym.verity.config.AppConfig
import com.evernym.verity.util2.UrlParam

case class MockEnv(name: String,
                   edgeAgent: MockEdgeAgent,
                   cloudAgent: MockCloudAgent,
                   agencyEdgeAgent: MockEdgeAgent,
                   othersMockEnvOpt: Option[MockEnv] = None) {

  def othersMockEnv: MockEnv = othersMockEnvOpt.getOrElse(
    throw new RuntimeException("others mock edge agent not yet set")
  )

  def withOthersMockEnvSet(mockEnv: MockEnv): MockEnv = {
    copy(othersMockEnvOpt = Option(mockEnv))
  }
}

object MockEnvUtil {

  def buildMockCloudAgent(mockAgencyEdgeAgent: MockEdgeAgent): MockCloudAgent = {
    val mcea = new MockCloudAgent(mockAgencyEdgeAgent.agencyEndpoint, mockAgencyEdgeAgent.appConfig)
    mcea.agencyPublicDid = Option(mockAgencyEdgeAgent.myDIDDetail.prepareAgencyIdentity)
    mcea
  }

  def buildMockEdgeAgent(mockAgencyEdgeAgent: MockEdgeAgent): MockEdgeAgent = {
    val mcea = new MockEdgeAgent(mockAgencyEdgeAgent.agencyEndpoint, mockAgencyEdgeAgent.appConfig)
    mcea.agencyPublicDid = Option(mockAgencyEdgeAgent.myDIDDetail.prepareAgencyIdentity)
    mcea
  }

  def buildNewEnv(name: String, appConfig: AppConfig, cloudAgentUrl: String): MockEnv = {
    val mockAgencyAdmin = new MockEdgeAgent(UrlParam(cloudAgentUrl), appConfig)
    val mockCloudAgent: MockCloudAgent = MockEnvUtil.buildMockCloudAgent(mockAgencyAdmin)
    val mockEdgeAgent: MockEdgeAgent = MockEnvUtil.buildMockEdgeAgent(mockAgencyAdmin)
    MockEnv(name, mockEdgeAgent, mockCloudAgent, mockAgencyAdmin)
  }
}

case class MockEnvUtil(system: ActorSystem, appConfig: AppConfig) {
  private var environments = List.empty[MockEnv]

  def getEnv(name: String): MockEnv = environments.find(_.name == name).getOrElse(
    throw new RuntimeException("mock environment not found with name: " + name)
  )

  def addNewEnv(mockEnv: MockEnv): Unit = {
    environments = environments :+ mockEnv
  }

}
