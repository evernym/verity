package com.evernym.integrationtests.e2e.apis

import java.util.UUID

import com.evernym.integrationtests.e2e.env.AppInstance.Verity
import com.evernym.integrationtests.e2e.env.EnvUtils.IntegrationEnv
import com.evernym.integrationtests.e2e.env.{AppInstance, IntegrationTestEnv}
import com.evernym.integrationtests.e2e.flow._
import com.evernym.integrationtests.e2e.scenario.Scenario.runScenario
import com.evernym.integrationtests.e2e.scenario.{Scenario, ScenarioAppEnvironment}
import com.evernym.verity.fixture.TempDir
import com.evernym.verity.sdk.protocols.relationship.v1_0.GoalCode
import com.evernym.verity.sdk.protocols.writecreddef.v0_6.WriteCredentialDefinitionV0_6
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.testkit.LedgerClient.buildLedgerUtil
import com.evernym.verity.testkit.util.LedgerUtil
import com.evernym.verity.util.StrUtil
import org.scalatest.concurrent.Eventually


class SdkFlowSpec
  extends BasicSpec
  with TempDir
  with IntegrationEnv
  with InteractiveSdkFlow
  with SetupFlow
  with AdminFlow
  with MetricsFlow
  with MessageTrackingFlow
  with Eventually {

  override def environmentName: String = sys.env.getOrElse("ENVIRONMENT_NAME", StrUtil.classToKebab[SdkFlowSpec])

  def specifySdkType(env: IntegrationTestEnv): IntegrationTestEnv = env
  def appEnv: IntegrationTestEnv = specifySdkType(testEnv)

  val cas1: AppInstance.AppInstance = testEnv.instance_!(APP_NAME_CAS_1).appInstance
  val verity1: AppInstance.AppInstance = testEnv.instance_!(APP_NAME_VERITY_1).appInstance

  runScenario("sdkFlow") {

    implicit val scenario: Scenario = Scenario(
      "SDK Workflow test for 0.6 Protocols",
      List(cas1, verity1),
      suiteTempDir,
      projectDir,
      defaultTimeout = testEnv.timeout
    )

    val apps = ScenarioAppEnvironment(scenario, appEnv)

    val sdkUnderTest = apps(verity1)
      .sdk
      .getOrElse(throw new Exception("Verity SDK must be defined for this Suite"))
      .sdkType

    s"Basic SDK Interaction Test for $sdkUnderTest" - {
      lazy val ledgerUtil: LedgerUtil = buildLedgerUtil(
        appEnv.config,
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

      "basic interaction" - {
        sdkBasicInteractions(apps, ledgerUtil)
      }

      "oob interaction" - {
        sdkOobInteractions(apps, ledgerUtil)
      }

      "test metrics" - {
        testMetricsForVerityInstances(apps)
      }

      "test message tracking" - {
        testMessageTrackingForVerityInstances(apps)
      }

      "sdk cleanup" - {
        apps.forEachApplication(cleanupSdk)
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
    val sdk = apps(verity1).sdk.get

    setupIssuer(sdk, ledgerUtil)

    updateConfigs(
      sdk,
      ledgerUtil,
      "name1",
      "/logo_url.ico"
    )

//    val schemaName = "license"+UUID.randomUUID().toString.substring(0, 8)
//    writeSchema(
//      sdk,
//      ledgerUtil,
//      schemaName,
//      "0.1",
//      "license_num",
//      "name"
//    )
//
//    writeCredDef(
//      sdk,
//      "cred_name1",
//      "tag",
//      WriteCredentialDefinitionV0_6.disabledRegistryConfig(),
//      schemaName,
//      "0.1",
//      ledgerUtil
//    )
  }

  def sdkBasicInteractions(apps: ScenarioAppEnvironment, ledgerUtil: LedgerUtil)(implicit scenario: Scenario): Unit = {

    val connectionId = UUID.randomUUID().toString

    connect_1_0(apps(verity1), apps(cas1), connectionId, "label")

    out_of_band_with_connect_1_0(apps(verity1), apps(cas1), connectionId, "label",
       GoalCode.ISSUE_VC)

//    issueCredential_1_0(
//      apps(verity1),
//      apps(cas1),
//      connectionId,
//      Map("license_num" -> "123", "name" -> "Bob"),
//      "cred_name1",
//      "tag"
//    )
//
//    presentProof_1_0(
//      apps(verity1),
//      apps(cas1),
//      connectionId,
//      "proof-request-1",
//      Seq("name", "license_num")
//    )

//    committedAnswer(
//      apps(verity1),
//      apps(cas1),
//      connectionId,
//      "To be or to not be?",
//      "The second classic philosophical questions",
//      Seq("be", "not be"),
//      "be",
//      requireSig = true
//    )

    basicMessage(
      apps(verity1),
      apps(cas1),
      connectionId,
      "Hello, World!",
      "2018-1-19T01:24:00-000",
      "en"
    )
  }

  def sdkOobInteractions(apps: ScenarioAppEnvironment, ledgerUtil: LedgerUtil)(implicit scenario: Scenario): Unit = {
    val connectionId = UUID.randomUUID().toString

    issueCredentialViaOob_1_0(
      apps(verity1),
      apps(cas1),
      connectionId,
      Map("license_num" -> "123", "name" -> "Bob"),
      "cred_name1",
      "tag"
    )

    presentProofViaOob_1_0(
      apps(verity1),
      apps(cas1),
      connectionId,
      "proof-request-1",
      Seq("name", "license_num")
    )

  }

  def testMetricsForVerityInstances(apps: ScenarioAppEnvironment): Unit = {
    apps.forEachApplication(testMetrics)
  }

  def testMessageTrackingForVerityInstances(apps: ScenarioAppEnvironment): Unit = {
    apps.forEachApplication(testMessageTracking)
    testMessageTrackingMetrics(apps.applications.head._2)
  }

}

object SdkFlowSpec {
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
