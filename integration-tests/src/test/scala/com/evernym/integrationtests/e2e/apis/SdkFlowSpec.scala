package com.evernym.integrationtests.e2e.apis

import com.evernym.integrationtests.e2e.apis.SdkFlowSpec.metricKey
import com.evernym.integrationtests.e2e.env.AppInstance.Verity
import com.evernym.integrationtests.e2e.env.EnvUtils.IntegrationEnv
import com.evernym.integrationtests.e2e.env.{AppInstance, IntegrationTestEnv}
import com.evernym.integrationtests.e2e.flow._
import com.evernym.integrationtests.e2e.scenario.Scenario.runScenario
import com.evernym.integrationtests.e2e.scenario.{Scenario, ScenarioAppEnvironment}
import com.evernym.verity.did.didcomm.v1.messages.MsgFamily
import com.evernym.verity.fixture.TempDir
import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByClass
import com.evernym.verity.protocol.protocols.committedAnswer.v_1_0.CommittedAnswerMsgFamily
import com.evernym.verity.protocol.protocols.connections.v_1_0.ConnectionsMsgFamily
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.IssueCredMsgFamily
import com.evernym.verity.protocol.protocols.outofband.v_1_0.OutOfBandMsgFamily
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.PresentProofMsgFamily
import com.evernym.verity.sdk.protocols.relationship.v1_0.GoalCode
import com.evernym.verity.sdk.protocols.writecreddef.v0_6.WriteCredentialDefinitionV0_6
import com.evernym.verity.testkit.{BasicSpec, CancelGloballyAfterFailure}
import com.evernym.verity.testkit.LedgerClient.buildLedgerUtil
import com.evernym.verity.testkit.util.LedgerUtil
import com.evernym.verity.util.StrUtil
import com.typesafe.scalalogging.Logger
import org.scalatest.concurrent.Eventually

import java.util.UUID
import com.evernym.verity.util2.ExecutionContextProvider


class SdkFlowSpec
  extends BasicSpec
    with TempDir
    with IntegrationEnv
    with InteractiveSdkFlow
    with SetupFlow
    with AdminFlow
    with MetricsFlow
    with CancelGloballyAfterFailure
    with Eventually {

  override val logger: Logger = getLoggerByClass(getClass)

  override def environmentName: String = sys.env.getOrElse("ENVIRONMENT_NAME", StrUtil.classToKebab[SdkFlowSpec])

  def specifySdkType(env: IntegrationTestEnv): IntegrationTestEnv = env
  def appEnv: IntegrationTestEnv = specifySdkType(testEnv)
  lazy val ecp: ExecutionContextProvider = new ExecutionContextProvider(appEnv.config)

  val cas1: AppInstance.AppInstance = testEnv.instance_!(APP_NAME_CAS_1).appInstance
  val verity1: AppInstance.AppInstance = testEnv.instance_!(APP_NAME_VERITY_1).appInstance
  val limitsCredDefName = "creds_for_limits"

  runScenario("sdkFlow") ( Scenario(
    "SDK Workflow to test protocols",
    List(cas1, verity1),
    suiteTempDir,
    projectDir,
    defaultTimeout = testEnv.timeout) ) { implicit scenario =>

    val apps = ScenarioAppEnvironment(scenario, appEnv, ecp)

    val sdkUnderTest = apps(verity1)
      .sdk
      .getOrElse(throw new Exception("Verity SDK must be defined for this Suite"))
      .sdkType

    s"Basic SDK Interaction Test for $sdkUnderTest" - {
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

      "basic interaction" - {
        sdkBasicInteractions(apps, ledgerUtil)
      }

      "oob interaction" - {
        sdkOobInteractions(apps, ledgerUtil)
      }

      "test metrics" - {
        testMetricsForVerityInstances(apps)
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

    setupIssuer(sdk, sdk, ledgerUtil, None)

    val schemaName = "license"+UUID.randomUUID().toString.substring(0, 8)

    writeSchemaNeedsEndorsement(
      sdk,
      ledgerUtil,
      schemaName,
      "0.1",
      "license_num",
      "first_name",
      "last_name")


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
      schemaName,
      "0.1",
      "license_num",
      "first_name",
      "last_name"
    )

//    writeCredDefNeedsEndorsement(
//      sdk,
//      schemaName,
//      "0.1",
//      "cred_name1",
//      "tag",
//      WriteCredentialDefinitionV0_6.disabledRegistryConfig(),
//      ledgerUtil
//    )

    writeCredDef(
      sdk,
      "cred_name1",
      "tag",
      WriteCredentialDefinitionV0_6.disabledRegistryConfig(),
      schemaName,
      "0.1",
      ledgerUtil
    )
  }

  def sdkBasicInteractions(apps: ScenarioAppEnvironment, ledgerUtil: LedgerUtil)(implicit scenario: Scenario): Unit = {

    val connectionId = UUID.randomUUID().toString

    connect_1_0(apps(verity1), apps(cas1), connectionId, "label")

    out_of_band_with_connect_1_0(apps(verity1), apps(cas1), connectionId, "label",
      GoalCode.ISSUE_VC)

    issueCredential_1_0(
      apps(verity1),
      apps(cas1),
      connectionId,
      Map("license_num" -> "123", "first_name" -> "Bob", "last_name" -> "Marley"),
      "cred_name1",
      "tag"
    )

    presentProof_1_0(
      apps(verity1),
      apps(cas1),
      connectionId,
      "proof-request-1",
      List(("first_name", "first_name", "Bob"), ("last_name", "last_name", "Marley"), ("license_num", "license_num", "123"))
    )

    presentProof_1_0_with_proposal(
      apps(verity1),
      apps(cas1),
      connectionId,
      "proof-request-1",
      Seq("first_name", "last_name", "license_num")
    )

    committedAnswer(
      apps(verity1),
      apps(cas1),
      connectionId,
      "To be or to not be?",
      "The second classic philosophical questions",
      Seq("be", "not be"),
      "be",
      requireSig = true
    )

    basicMessage(
      apps(verity1),
      apps(cas1),
      connectionId,
      "Hello, World!",
      "2018-1-19T01:24:00-000",
      "en"
    )

    sdkBasicInteractionsMetricCount(apps)
  }

  private def sdkBasicInteractionsMetricCount(apps: ScenarioAppEnvironment): Unit = {
    //The 'expectedMetricCount' will change depending how many times the app scenario ran a specific protocol
    Set(
      (ConnectionsMsgFamily, 2),
      (CommittedAnswerMsgFamily, 1),
      (OutOfBandMsgFamily, 1),
      (PresentProofMsgFamily, 2),
      (IssueCredMsgFamily, 1)
    ).foreach{ x =>
      if (apps(verity1).instance.isRunningLocally) {
        validateProtocolMetrics(apps(verity1), metricKey(x._1), expectedMetricCount=x._2)
      }
    }
  }

  def sdkOobInteractions(apps: ScenarioAppEnvironment, ledgerUtil: LedgerUtil)(implicit scenario: Scenario): Unit = {
    val connectionId = UUID.randomUUID().toString

    issueCredentialViaOob_1_0(
      apps(verity1),
      apps(cas1),
      connectionId,
      Map("license_num" -> "123", "first_name" -> "Bob", "last_name" -> "Marley"),
      "cred_name1",
      "tag"
    )

    presentProofViaOob_1_0(
      apps(verity1),
      apps(cas1),
      connectionId,
      "proof-request-1",
      Seq("first_name", "last_name", "license_num")
    )

    // Reuse connection from connection
    presentProofViaOob_1_0(
      apps(verity1),
      apps(cas1),
      connectionId,
      "proof-request-1",
      Seq("first_name", "last_name", "license_num"),
      true
    )

    issueCredentialViaOob_1_0(
      apps(verity1),
      apps(cas1),
      connectionId,
      Map("license_num" -> "123", "first_name" -> "Bob", "last_name" -> "Marley"),
      "cred_name1",
      "tag",
      true
    )
  }

  def testMetricsForVerityInstances(apps: ScenarioAppEnvironment): Unit = {
    apps.forEachApplication { a =>
      if(a.instance.isRunningLocally) testMetrics(a)
    }
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

  def metricKey(msgFamily: MsgFamily): String = s"${msgFamily.name}[${msgFamily.version}]"

}
