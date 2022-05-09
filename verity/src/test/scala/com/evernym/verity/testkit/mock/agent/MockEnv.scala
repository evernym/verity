package com.evernym.verity.testkit.mock.agent

import akka.actor.ActorSystem
import com.evernym.verity.actor.testkit.ActorSpec
import com.evernym.verity.config.AppConfig
import com.evernym.verity.testkit.BasicSpecBase
import com.evernym.verity.util2.{ExecutionContextProvider, UrlParam}

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

object MockEnvUtil extends ActorSpec with BasicSpecBase{

  def buildMockCloudAgent(mockAgencyEdgeAgent: MockEdgeAgent, executionContext: ExecutionContext): MockCloudAgent = {
    new MockCloudAgent(executionContext, mockAgencyEdgeAgent.agencyEndpoint, mockAgencyEdgeAgent.appConfig, system = system)
  }

  def buildMockEdgeAgent(mockAgencyEdgeAgent: MockEdgeAgent, executionContext: ExecutionContext): MockEdgeAgent = {
    new MockEdgeAgent(mockAgencyEdgeAgent.agencyEndpoint, mockAgencyEdgeAgent.appConfig, executionContext, system = system)
  }

  def buildNewEnv(name: String, appConfig: AppConfig, cloudAgentUrl: String, executionContext: ExecutionContext): MockEnv = {
    val mockAgencyAdmin = new MockEdgeAgent(UrlParam(cloudAgentUrl), appConfig, executionContext, system = system)
    val mockCloudAgent: MockCloudAgent = MockEnvUtil.buildMockCloudAgent(mockAgencyAdmin, executionContext)
    val mockEdgeAgent: MockEdgeAgent = MockEnvUtil.buildMockEdgeAgent(mockAgencyAdmin, executionContext)
    MockEnv(name, mockEdgeAgent, mockCloudAgent, mockAgencyAdmin)
  }

  def executionContextProvider: ExecutionContextProvider = new ExecutionContextProvider(appConfig)
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
