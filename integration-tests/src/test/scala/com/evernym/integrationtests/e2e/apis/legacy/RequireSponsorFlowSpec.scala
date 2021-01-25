package com.evernym.integrationtests.e2e.apis.legacy

import com.evernym.integrationtests.e2e.scenario.Scenario.isRunScenario
import com.evernym.integrationtests.e2e.scenario.Scenario
import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.config.AppConfig
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.typesafe.config.{Config, ConfigValueFactory}
import com.typesafe.scalalogging.Logger

//TODO: This entire file can be removed when agent provisioning 0.5 and 0.6 are removed
class RequireSponsorFlowSpec
  extends LegacyApiFlowBaseSpec {

  override val logger: Logger = getLoggerByClass(getClass)

  override def environmentName: String = "require-sponsor"

  private def newPoolNameConfig(): Config = (new TestAppConfig)
    .config
    .withValue("verity.lib-indy.ledger.pool-name", ConfigValueFactory.fromAnyRef("require-sponsor-pool"))

  override lazy val appConfig: AppConfig = new TestAppConfig(Some(newPoolNameConfig()))

  def appNameCAS: String = APP_NAME_CAS_2
  def appNameEAS: String = APP_NAME_EAS_2

  def oldProvisioningProtocolsFail(ce: ClientEnvironment)(implicit aae: AgencyAdminEnvironment): Unit = {
    implicit def sc: Scenario = ce.scenario
    s"${ce.scenario.name}" - {
      ce.enterprise.setupTillAgentCreationFailsDeprecated
    }
  }

  //test all apis without server restart in between
  val scenario1 = Scenario(
    "API caller scenario 1 (general)",
    requiredAppInstances,
    suiteTempDir,
    projectDir,
    connIds = Set("connId1", "connId2")
  )
  val clientEnv1 = ClientEnvironment (
    scenario1,
    consumerAgencyEndpoint = consumerAgencyEndpoint,
    enterpriseAgencyEndpoint = enterpriseAgencyEndpoint)

  if ( isRunScenario("scenario1") ) {
    oldProvisioningProtocolsFail(clientEnv1)(agencyAdminEnv)
  }
}
