package com.evernym.integrationtests.e2e.apis

import com.evernym.integrationtests.e2e.env.AppInstance.Verity
import com.evernym.integrationtests.e2e.env.EnvUtils.IntegrationEnv
import com.evernym.integrationtests.e2e.env.{AppInstance, IntegrationTestEnv}
import com.evernym.integrationtests.e2e.flow._
import com.evernym.integrationtests.e2e.scenario.Scenario.runScenario
import com.evernym.integrationtests.e2e.scenario.{Scenario, ScenarioAppEnvironment}
import com.evernym.verity.fixture.TempDir
import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByClass
import com.evernym.verity.sdk.protocols.writecreddef.v0_6.WriteCredentialDefinitionV0_6
import com.evernym.verity.testkit.{BasicSpec, CancelGloballyAfterFailure}
import com.evernym.verity.testkit.LedgerClient.buildLedgerUtil
import com.evernym.verity.testkit.util.LedgerUtil
import com.evernym.verity.util.StrUtil
import com.evernym.verity.util2.ExecutionContextProvider
import com.typesafe.scalalogging.Logger
import org.scalatest.concurrent.Eventually


class MultiTenantSpec
  extends BasicSpec
    with TempDir
    with IntegrationEnv
    with InteractiveSdkFlow
    with SetupFlow
    with AdminFlow
    with MetricsFlow
    with CancelGloballyAfterFailure
    with Eventually{

  override val logger: Logger = getLoggerByClass(getClass)

  override def environmentName: String = StrUtil.classToKebab[MultiTenantSpec]

  def specifySdkType(env: IntegrationTestEnv): IntegrationTestEnv = env
  def appEnv: IntegrationTestEnv = specifySdkType(testEnv)
  lazy val ecp: ExecutionContextProvider = new ExecutionContextProvider(appEnv.config)

  val cas1: AppInstance.AppInstance = testEnv.instance_!(APP_NAME_CAS_1).appInstance
  val verity1: AppInstance.AppInstance = testEnv.instance_!(APP_NAME_VERITY_1).appInstance


  runScenario("multiTenant") ( Scenario(
    "Multi Tenant Workflow test for 0.6 Protocols",
    List(cas1, verity1),
    suiteTempDir,
    projectDir,
    defaultTimeout = testEnv.timeout) ){implicit scenario =>

    val apps = ScenarioAppEnvironment(scenario, appEnv, ecp)

    val sdkUnderTest = apps(verity1)
      .sdks
      .headOption
      .getOrElse(throw new Exception("Verity SDK must be defined for this Suite"))
      .sdkType

    s"Multi Tenant Interaction Test for $sdkUnderTest" - {
      lazy val ledgerUtil: LedgerUtil = buildLedgerUtil(
        appEnv.config,
        ecp.futureExecutionContext,
        Option(appEnv.ledgerConfig.submitterDID),
        Option(appEnv.ledgerConfig.submitterSeed),
        appEnv.ledgerConfig.submitterRole,
        genesisTxnPath = Some(appEnv.ledgerConfig.genesisFilePath)
      )

      "application setup" - {
        sdkAppSetupInteraction(apps, ledgerUtil)
      }

      "issuer setup" - {
        sdkIssuerSetupInteraction(apps, ledgerUtil)
      }
    }
  }

  def sdkAppSetupInteraction(apps: ScenarioAppEnvironment, ledgerUtil: LedgerUtil)(implicit scenario: Scenario): Unit = {
    apps.forEachApplication(availableSdk)
    apps.forEachApplication(setupApplication(_, ledgerUtil))
    apps.forEachApplication(fetchAgencyDetail)

    apps.forEachApplication(provisionAgent)
  }

  def sdkIssuerSetupInteraction(apps: ScenarioAppEnvironment, ledgerUtil: LedgerUtil)(implicit scenario: Scenario): Unit = {
    apps(verity1).sdks.foreach { sdk =>
      setupIssuer(sdk, ledgerUtil)
      writeIssuerToLedger(sdk, ledgerUtil)

      updateConfigs(
        sdk,
        ledgerUtil,
        "name1",
        "/logo_url.ico"
      )

      writeSchema(
        sdk,
        ledgerUtil,
        "license",
        "0.1",
        "license_num",
        "name"
      )

      writeCredDef(
        sdk,
        "cred_name1",
        "tag",
        WriteCredentialDefinitionV0_6.disabledRegistryConfig(),
        "license",
        "0.1",
        ledgerUtil
      )
    }

  }
}

object MultiTenantSpec {
  def specifySdkForType(sdkType: String, version: String, env: IntegrationTestEnv): IntegrationTestEnv = {
    val sdks = env.sdks
    val specified = sdks
      .map { s =>
        s.verityInstance.appType match {
          case Verity => s.copy(sdkTypeStr=sdkType, version=Some(version))
          case _ => s
        }
      }

    env.copy(sdks=specified)
  }
}

