package com.evernym.integrationtests.e2e.apis.legacy.vcx

import com.evernym.integrationtests.e2e.apis.legacy.AgencyAdminEnvironment
import com.evernym.integrationtests.e2e.apis.legacy.base.LibVcxProvider
import com.evernym.integrationtests.e2e.env.AppInstance.AppInstance
import com.evernym.integrationtests.e2e.env.EnvUtils.IntegrationEnv
import com.evernym.integrationtests.e2e.flow.SetupFlow
import com.evernym.integrationtests.e2e.scenario.Scenario
import com.evernym.verity.fixture.TempDir
import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByName
import com.typesafe.scalalogging.Logger


trait BaseVcxFlowSpec
  extends LibVcxProvider
    with TempDir
    with IntegrationEnv
    with SetupFlow {

  override val logger: Logger = getLoggerByName("VcxFlowSpec")

  def appNameCAS: String = APP_NAME_CAS_1
  def appNameEAS: String = APP_NAME_EAS_1

  val cas = testEnv.instance_!(appNameCAS)
  val eas = testEnv.instance_!(appNameEAS)

  val requiredAppInstances: List[AppInstance] = List(cas.appInstance, eas.appInstance)
  val agencyScenario = Scenario("Agency setup scenario", requiredAppInstances, suiteTempDir, projectDir)

  override val genesisTxnFilePath: String = testEnv.ledgerConfig.genesisFilePath

  val agencyAdminEnv: AgencyAdminEnvironment = AgencyAdminEnvironment(
    agencyScenario,
    casVerityInstance = testEnv.instance_!(appNameCAS),
    easVerityInstance = testEnv.instance_!(appNameEAS),
    executionContextProvider
  )

  setupAgency(agencyAdminEnv)

  //agency environment detail
  def setupAgency(ae: AgencyAdminEnvironment): Unit = {
    implicit def sc: Scenario = ae.scenario
    s"${ae.scenario.name}" - {
      "Consumer Agency Admin" - {
        setupApplication(ae.consumerAgencyAdmin, ledgerUtil)
      }

      "Enterprise Agency Admin" - {
        setupApplication(ae.enterpriseAgencyAdmin, ledgerUtil)
      }
    }
  }
}