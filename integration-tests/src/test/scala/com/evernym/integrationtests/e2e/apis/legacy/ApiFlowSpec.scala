package com.evernym.integrationtests.e2e.apis.legacy

import com.evernym.integrationtests.e2e.scenario.Scenario._
import com.evernym.integrationtests.e2e.scenario.Scenario
import com.evernym.integrationtests.e2e.util.TestExecutionContextProvider
import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByClass
import com.evernym.verity.util2.ExecutionContextProvider
import com.typesafe.scalalogging.Logger

class ApiFlowSpec
  extends LegacyApiFlowBaseSpec {

  override val logger: Logger = getLoggerByClass(getClass)

  def appNameCAS: String = APP_NAME_CAS_1
  def appNameEAS: String = APP_NAME_EAS_1

  //test all apis without server restart in between
  lazy val scenario1 = Scenario(
    "API caller scenario 1 (general)",
    requiredAppInstances,
    suiteTempDir,
    projectDir,
    connIds = Set("connId1", "connId2")
  )
  lazy val clientEnv1 = ClientEnvironment (
    scenario1,
    consumerAgencyEndpoint = consumerAgencyEndpoint,
    enterpriseAgencyEndpoint = enterpriseAgencyEndpoint)

  if ( isRunScenario("scenario1") ) {
    //test all apis with agency service restart in between randomly
    val scenario2 = Scenario(
      "API caller scenario 1 (mostly MFV 0.5)",
      requiredAppInstances,
      suiteTempDir,
      projectDir,
      connIds = Set("connId1", "connId2"),
      restartVerityRandomly = restartVerityRandomly()
    )
    val clientEnv2: ClientEnvironment = clientEnv1.copy(scenario = scenario2)
    generalEndToEndFlowScenario(clientEnv2)(agencyAdminEnv)
  }

  if ( isRunScenario("scenario2") ) {
    //test all apis with agency service restart in between each of them
    val scenario2 = Scenario(
      "API caller scenario 2 (mostly MFV 0.6 & MFV 0.7)",
      requiredAppInstances,
      suiteTempDir,
      projectDir,
      connIds = Set("connId3", "connId4")
    )
    val clientEnv3: ClientEnvironment = clientEnv1.copy(scenario = scenario2)
    generalEndToEndFlowScenario_MFV_0_6(clientEnv3)(agencyAdminEnv)
  }

  override def executionContextProvider: ExecutionContextProvider = TestExecutionContextProvider.ecp
}