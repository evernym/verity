package com.evernym.integrationtests.e2e.apis.limits

import com.evernym.integrationtests.e2e.apis.SdkFlowSpec.metricKey
import com.evernym.integrationtests.e2e.env.EnvUtils.IntegrationEnv
import com.evernym.integrationtests.e2e.env.{AppInstance, IntegrationTestEnv}
import com.evernym.integrationtests.e2e.flow._
import com.evernym.integrationtests.e2e.scenario.Scenario.runScenario
import com.evernym.integrationtests.e2e.scenario.{Scenario, ScenarioAppEnvironment}
import com.evernym.verity.fixture.TempDir
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.evernym.verity.protocol.protocols.committedAnswer.v_1_0.CommittedAnswerMsgFamily
import com.evernym.verity.protocol.protocols.connections.v_1_0.ConnectionsMsgFamily
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.IssueCredMsgFamily
import com.evernym.verity.protocol.protocols.outofband.v_1_0.OutOfBandMsgFamily
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.PresentProofMsgFamily
import com.evernym.verity.sdk.protocols.relationship.v1_0.GoalCode
import com.evernym.verity.sdk.protocols.writecreddef.v0_6.WriteCredentialDefinitionV0_6
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.testkit.LedgerClient.buildLedgerUtil
import com.evernym.verity.testkit.util.LedgerUtil
import com.evernym.verity.util.StrUtil
import com.typesafe.scalalogging.Logger
import org.scalatest.concurrent.Eventually

import java.util.UUID
import scala.concurrent.duration.DurationInt


class LimitsFlowSpec
  extends BasicSpec
  with TempDir
  with IntegrationEnv
  with InteractiveSdkFlow
  with SetupFlow
  with AdminFlow
  with MetricsFlow
  with Eventually {

  override val logger: Logger = getLoggerByClass(getClass)

  override def environmentName: String = sys.env.getOrElse("ENVIRONMENT_NAME", StrUtil.classToKebab[LimitsFlowSpec])

  def specifySdkType(env: IntegrationTestEnv): IntegrationTestEnv = env
  def appEnv: IntegrationTestEnv = specifySdkType(testEnv)

  val cas1: AppInstance.AppInstance = testEnv.instance_!(APP_NAME_CAS_1).appInstance
  val verity1: AppInstance.AppInstance = testEnv.instance_!(APP_NAME_VERITY_1).appInstance
  
  val limitsCredDefName = "creds_for_limits"

  val longAttrList = (1 to 125).map(i => s"attrib$i")

  runScenario("sdkFlow") {

    implicit val scenario: Scenario = Scenario(
      "SDK Workflow limits test for 0.6 Protocols",
      List(cas1, verity1),
      suiteTempDir,
      projectDir,
      defaultTimeout = Some(30.seconds)
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


      //todo implement other cases
//
//      "oob interaction" - {
//        sdkOobInteractions(apps, ledgerUtil)
//      }
//
//      "test metrics" - {
//        testMetricsForVerityInstances(apps)
//      }

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

    val schemaName1 = "multipleAttrs"+UUID.randomUUID().toString.substring(0, 8)

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
      schemaName1,
      "0.1",
      longAttrList: _*
    )

    writeCredDef(
      sdk,
      "cred_name1",
      "tag",
      WriteCredentialDefinitionV0_6.disabledRegistryConfig(),
      schemaName1,
      "0.1",
      ledgerUtil
    )

    val schemaName2 = "largeAttrs"+UUID.randomUUID().toString.substring(0, 8)
    val longString = "0123456789"*24
    val attrList2 = (1 to 10).map(i => s"attrib$i")

    writeSchema(
      sdk,
      ledgerUtil,
      schemaName2,
      "0.2",
      attrList2 : _*
    )

    writeCredDef(
      sdk,
      "cred_name2",
      "tag",
      WriteCredentialDefinitionV0_6.disabledRegistryConfig(),
      schemaName2,
      "0.2",
      ledgerUtil
    )

    val limitsSchema = "something"+UUID.randomUUID().toString.substring(0, 8)

    writeSchema(
      sdk,
      ledgerUtil,
      limitsSchema,
      "0.1",
      (0 to 9).map(i => s"attr$i") : _*
    )

    writeCredDef(
      sdk,
      limitsCredDefName,
      "tag",
      WriteCredentialDefinitionV0_6.disabledRegistryConfig(),
      limitsSchema,
      "0.1",
      ledgerUtil
    )

    // todo this is error cases
    val schemaName3 = "tooMayAttrs"+UUID.randomUUID().toString.substring(0, 8)
    val attrList3 = (1 to 1000).map(i => s"attrib$i")

    writeFailingSchema(
      sdk,
      sdk,
      ledgerUtil,
      schemaName3,
      "0.3",
      "A value being processed is not valid",
      attrList3 : _*
    )


    val schemaName4 = "tooLongAttrs"+UUID.randomUUID().toString.substring(0, 8)
    val longString4 = "0123456789"*1000
    val attrList4 = (1 to 3).map(i => s"$longString4$i")

    writeFailingSchema(
      sdk,
      sdk,
      ledgerUtil,
      schemaName4,
      "0.4",
      "longer than 256 symbols",
      attrList4 : _*
    )


  }

  def sdkBasicInteractions(apps: ScenarioAppEnvironment, ledgerUtil: LedgerUtil)(implicit scenario: Scenario): Unit = {

    val connectionId = UUID.randomUUID().toString

    connect_1_0(apps(verity1), apps(cas1), connectionId, "label")

    out_of_band_with_connect_1_0(apps(verity1), apps(cas1), connectionId, "label",
       GoalCode.ISSUE_VC)


    val strBelowLimit ="1234567890"*2200
    val strAboveLimit ="1234567890"*7000

    issueCredential_1_0(
      apps(verity1),
      apps(cas1),
      connectionId,
      (0 to 9).map{i=> s"attr$i" -> strBelowLimit}.toMap,
      limitsCredDefName,
      "tag"
    )

    val issuerSdk = apps(verity1).sdks.head
    val holderSdk = apps(cas1).sdks.head
    issueCredential_1_0_expectingError(
      issuerSdk,
      holderSdk,
      connectionId,
      (0 to 9).map{i=> s"attr$i" -> strAboveLimit}.toMap,
      limitsCredDefName,
      "tag",
      "Payload size is too big"
    )

    // large amount of attrs
    val longAttrsMap = longAttrList.map( attr => attr -> "someValue" ).toMap
    issueCredential_1_0(
      apps(verity1),
      apps(cas1),
      connectionId,
      longAttrsMap,
      "cred_name1",
      "tag"
    )

    val longString = "0123456789"*20000
    val hugeAttrsMap = (1 to 10).map( i => s"attrib$i" -> longString ).toMap
    issueCredential_1_0(
      apps(verity1),
      apps(cas1),
      connectionId,
      hugeAttrsMap,
      "cred_name2",
      "tag"
    )

    val largeProofList = (1 to 1000).map(i => s"proof$i").toSeq
    presentProof_1_0(
      apps(verity1),
      apps(cas1),
      connectionId,
      "proof-request-1",
      largeProofList
    )

    val longString2 = "1234567890"*24000
    committedAnswer(
      apps(verity1),
      apps(cas1),
      connectionId,
      "Long description",
      longString2,
      Seq("Ok", "Not ok"),
      "Ok",
      requireSig = true
    )

    val longSeq = (0 to 4300).map(i => s"answer$i")
    committedAnswer(
      apps(verity1),
      apps(cas1),
      connectionId,
      "Multiple answers",
      "Description",
      longSeq,
      "answer0",
      requireSig = true
    )

    val longAnswer = "1234567890"*15000
    committedAnswer(
      apps(verity1),
      apps(cas1),
      connectionId,
      "Long answer",
      "Description",
      Seq(longAnswer),
      longAnswer,
      requireSig = true
    )

    //todo specify limits in configuration after merging with !260

    val longStringAboveLimit = "1234567890"*27000
    committedAnswerWithError(
      apps(verity1),
      apps(cas1),
      connectionId,
      "Long description",
      longStringAboveLimit,
      Seq("Ok", "Not ok"),
      requireSig = true,
      "Payload size is too big"
    )

    val longSeqAboveLimit = (0 to 5000).map(i => s"answer$i")
    committedAnswerWithError(
      apps(verity1),
      apps(cas1),
      connectionId,
      "Multiple answers",
      "Description",
      longSeqAboveLimit,
      requireSig = true,
      "Payload size is too big"
    )

    val longAnswerAboveLimit = "1234567890"*25000
    committedAnswerWithError(
      apps(verity1),
      apps(cas1),
      connectionId,
      "Long answer",
      "Description",
      Seq(longAnswerAboveLimit),
      requireSig = true,
      "Payload size is too big"
    )

    /*presentProof_1_0_with_proposal(
      apps(verity1),
      apps(cas1),
      connectionId,
      "proof-request-1",
      Seq("first_name", "last_name", "license_num")
    )

    basicMessage(
      apps(verity1),
      apps(cas1),
      connectionId,
      "Hello, World!",
      "2018-1-19T01:24:00-000",
      "en"
    )

    sdkBasicInteractionsMetricCount(apps)*/
  }

  private def sdkBasicInteractionsMetricCount(apps: ScenarioAppEnvironment): Unit = {
    //The 'expectedMetricCount' will change depending how many times the app scenario ran a specific protocol
    Set(
      (ConnectionsMsgFamily, 2),
      (CommittedAnswerMsgFamily, 1),
      (OutOfBandMsgFamily, 1),
      (PresentProofMsgFamily, 2),
      (IssueCredMsgFamily, 1)
    ).foreach(x => validateProtocolMetrics(apps(verity1), metricKey(x._1), expectedMetricCount=x._2))
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
    apps.forEachApplication(testMetrics)
  }

}


