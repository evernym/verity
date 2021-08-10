package com.evernym.verity.testkit.mock.agent

import akka.actor.ActorSystem
import com.evernym.verity.config.AppConfig
import com.evernym.verity.util2.UrlParam

import scala.concurrent.ExecutionContext

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

  def buildMockCloudAgent(mockAgencyEdgeAgent: MockEdgeAgent, executionContext: ExecutionContext, walletExecutionContext: ExecutionContext): MockCloudAgent = {
    val mcea = new MockCloudAgent(executionContext, walletExecutionContext, mockAgencyEdgeAgent.agencyEndpoint, mockAgencyEdgeAgent.appConfig)
    mcea.agencyPublicDid = Option(mockAgencyEdgeAgent.myDIDDetail.prepareAgencyIdentity)
    mcea
  }

  def buildMockEdgeAgent(mockAgencyEdgeAgent: MockEdgeAgent, executionContext: ExecutionContext, walletExecutionContext: ExecutionContext): MockEdgeAgent = {
    val mcea = new MockEdgeAgent(mockAgencyEdgeAgent.agencyEndpoint, mockAgencyEdgeAgent.appConfig, executionContext, walletExecutionContext)
    mcea.agencyPublicDid = Option(mockAgencyEdgeAgent.myDIDDetail.prepareAgencyIdentity)
    mcea
  }

  def buildNewEnv(name: String, appConfig: AppConfig, cloudAgentUrl: String, executionContext: ExecutionContext, walletExecutionContext: ExecutionContext): MockEnv = {
    val mockAgencyAdmin = new MockEdgeAgent(UrlParam(cloudAgentUrl), appConfig, executionContext, walletExecutionContext)
    val mockCloudAgent: MockCloudAgent = MockEnvUtil.buildMockCloudAgent(mockAgencyAdmin, executionContext, walletExecutionContext)
    val mockEdgeAgent: MockEdgeAgent = MockEnvUtil.buildMockEdgeAgent(mockAgencyAdmin, executionContext, walletExecutionContext)
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
