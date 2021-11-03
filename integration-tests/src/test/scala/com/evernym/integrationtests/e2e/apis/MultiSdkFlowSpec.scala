package com.evernym.integrationtests.e2e.apis

import com.evernym.integrationtests.e2e.env.EnvUtils.IntegrationEnv
import com.evernym.integrationtests.e2e.env.{AppInstance, IntegrationTestEnv}
import com.evernym.integrationtests.e2e.flow.InteractiveSdkFlow.receivingSdk
import com.evernym.integrationtests.e2e.flow.{AdminFlow, InteractiveSdkFlow, SetupFlow, VerityConfigFlow}
import com.evernym.integrationtests.e2e.scenario.Scenario.runScenario
import com.evernym.integrationtests.e2e.scenario.{Scenario, ScenarioAppEnvironment}
import com.evernym.integrationtests.e2e.sdk.VeritySdkProvider
import com.evernym.verity.config.ConfigConstants._
import com.evernym.verity.config.ConfigUtilBaseSpec
import com.evernym.verity.fixture.TempDir
import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByClass
import com.evernym.verity.sdk.protocols.writecreddef.v0_6.WriteCredentialDefinitionV0_6
import com.evernym.verity.testkit.{BasicSpec, CancelGloballyAfterFailure}
import com.evernym.verity.testkit.LedgerClient.buildLedgerUtil
import com.evernym.verity.testkit.util.LedgerUtil
import com.evernym.verity.util.StrUtil
import com.typesafe.scalalogging.Logger
import org.scalatest.concurrent.Eventually
import java.io.IOException
import java.util.UUID

import com.evernym.verity.util2.ExecutionContextProvider

import scala.util.Random

class MultiSdkFlowSpec
  extends BasicSpec
  with TempDir
  with IntegrationEnv
  with InteractiveSdkFlow
  with SetupFlow
  with ConfigUtilBaseSpec
  with VerityConfigFlow
  with AdminFlow
  with CancelGloballyAfterFailure
  with Eventually {

  override val logger: Logger = getLoggerByClass(getClass)

  override def environmentName: String = StrUtil.classToKebab[MultiSdkFlowSpec]
  lazy val ecp: ExecutionContextProvider = new ExecutionContextProvider(appEnv.config)

  def specifySdkType(env: IntegrationTestEnv): IntegrationTestEnv = env
  def appEnv: IntegrationTestEnv = specifySdkType(testEnv)

  val cas1: AppInstance.AppInstance = testEnv.instance_!(APP_NAME_CAS_1).appInstance
  val verity1: AppInstance.AppInstance = testEnv.instance_!(APP_NAME_VERITY_1).appInstance

  runScenario("multiSdkFlow") (Scenario(
    "SDK Workflow test for 0.6 Protocols",
    List(cas1, verity1),
    suiteTempDir,
    projectDir)) { implicit scenario =>

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
        genesisTxnPath = Some(appEnv.ledgerConfig.genesisFilePath)
      )

      sdkBasicInteraction(apps, ledgerUtil)

      checkAuthedAndUnAuthedInteractions(apps)

      authedInteractions(apps, ledgerUtil)

      apps.forEachApplication(cleanupSdk)
    }
  }


  def sdkBasicInteraction(apps: ScenarioAppEnvironment, ledgerUtil: LedgerUtil)(implicit scenario: Scenario): Unit = {
    apps.forEachApplication(availableSdk)

    apps.forEachApplication(setupApplication(_, ledgerUtil))
    apps.forEachApplication(fetchAgencyDetail)

    apps.forEachApplication(provisionAgent)
  }


  def checkAuthedAndUnAuthedInteractions(apps: ScenarioAppEnvironment)(implicit scenario: Scenario): Unit = {
    //verify that admin sdk is unauthorized
    connectingInteraction(apps, apps(verity1).sdk_!, "base admin sdk is authorized")

    //individual sdk gets setup
    val additionalSdks = setupNonAdminSdks(2, apps(verity1).sdk_!)
    val sdk2 = additionalSdks.head
    val sdk3 = additionalSdks.tail.head

    //no sdk is able to interact without being configured
    unauthorizedInteraction(apps, additionalSdks)             //sdk2 and sdk3 both are unauthorized

    //one of the sdk gets authorized and it is able to perform connection interaction
    updateVerityAuthedKeys(apps, List(sdk2))   //sdk2 got authorized
    connectingInteraction(apps, sdk2, "after sdk authorization")          //sdk2 able to do successful interaction
    unauthorizedInteraction(apps, List(sdk3))       //sdk3 still can't do successful interaction

    //another sdk gets authorized and it is able to perform connection interaction
    updateVerityAuthedKeys(apps, List(sdk2, sdk3))              //sdk2 and sdk3 got authorized
    connectingInteraction(apps, sdk2, "sdk still authorized")          //sdk2 still able to do successful interaction
    connectingInteraction(apps, sdk3, "after sdk authorization")               //sdk3 now able to do successful interaction

    //first authed sdk gets unauthorized and then it should not be able to successful interaction
    updateVerityAuthedKeys(apps, List(sdk3))        //sdk2 got unauthorized
    unauthorizedInteraction(apps, List(sdk2))  //sdk2 now can't do successful interaction
    connectingInteraction(apps, sdk3, "sdk still authorized")               //sdk3 can still do successful interaction

    //another authed sdk gets unauthorized and then it should not be able to successful interaction
    updateVerityAuthedKeys(apps, List.empty)                          //no authorized sdks
    unauthorizedInteraction(apps, List(sdk2, sdk3))  //sdk2 and sdk3 can't do successful interaction
  }

  def authedInteractions(apps: ScenarioAppEnvironment, ledgerUtil: LedgerUtil)(implicit scenario: Scenario): Unit = {
    "after authorizing sdks" - {
      "should be able to do successful message exchanges" - {

        val issuerMsgReceiverSdk = apps(verity1).sdk_!
        val additionalSdks = setupNonAdminSdks(2, apps(verity1).sdk_!)
        val issuerSdk2 = additionalSdks.head
        val issuerSdk3 = additionalSdks.tail.head

        val holderSdk = apps(cas1).sdks.head
        val holderReceiverSdk = apps(cas1).sdks.head

        updateVerityAuthedKeys(apps, List(issuerSdk2, issuerSdk3))

        setupIssuer(issuerSdk2, issuerMsgReceiverSdk, ledgerUtil)
        writeIssuerToLedger(issuerSdk2, issuerMsgReceiverSdk, ledgerUtil)

        writeSchema(
          issuerSdk2,
          issuerMsgReceiverSdk,
          ledgerUtil,
          "license",
          "0.1",
          "license_num",
          "name"
        )

        //  Without revocation
        writeCredDef(
          issuerSdk2,
          issuerMsgReceiverSdk,
          "cred_name1",
          "tag",
          WriteCredentialDefinitionV0_6.disabledRegistryConfig(),
          "license",
          "0.1",
          ledgerUtil
        )

        val inviter = apps(verity1)
        val invitee = apps(cas1)

        val connectionId = UUID.randomUUID().toString

        connect_1_0(
          inviter.name,
          issuerSdk2,
          issuerMsgReceiverSdk,
          invitee.name,
          receivingSdk(invitee),
          connectionId,
          "connection"
        )

        issueCredential_1_0(
          issuerSdk2,
          issuerMsgReceiverSdk,
          holderSdk,
          holderReceiverSdk,
          connectionId,
          Map("license_num" -> "123", "name" -> "Bob"),
          "cred_name1",
          "tag"
        )
      }
    }
  }

  def setupNonAdminSdks(count: Int,
                        provisionedSdk: VeritySdkProvider)
                       (implicit scenario: Scenario): List[VeritySdkProvider] = {
    (1 to count)
      .toList
      .map{ i =>
        val newName = provisionedSdk.sdkConfig.name + "_" + i
        val newSdk = VeritySdkProvider.fromSdkConfig(
          provisionedSdk.sdkConfig.copy(name = newName),
          scenario
        )
        s"setup non admin sdk (${newSdk.sdkConfig.name})" in {
          prepareSdkContextWithoutProvisioning(provisionedSdk, newSdk)
        }
        newSdk
      }
  }

  def unauthorizedInteraction(apps: ScenarioAppEnvironment, unAuthedSdks: List[VeritySdkProvider]): Unit = {
    unAuthedSdks.foreach { sdk =>
      s"unauthorized sdk interaction (${sdk.sdkConfig.name}(${Random.nextInt}))" - {
        "should fail with appropriate error" in {
          val ex = intercept[IOException] {
            val connectionId = UUID.randomUUID().toString
            val interSdk = InteractiveSdkFlow.receivingSdk(Option(sdk))
            val rel = interSdk.relationship_1_0(connectionId)
            rel.create(interSdk.context)
          }
          ex.getMessage shouldBe """Request failed! - 401 - {"statusCode":"GNR-108","statusMsg":"unauthorized"}"""
        }
      }
    }
  }

  def updateVerityAuthedKeys(apps: ScenarioAppEnvironment, sdksToBeAuthed: List[VeritySdkProvider])(implicit scenario: Scenario): Unit = {
    val adminSdk = apps(verity1).sdks.head
    val sdkNames = sdksToBeAuthed.map(_.sdkConfig.name).mkString(", ")
    s"admin updates sdk keys in verity config ($sdkNames)" - {
      "should be updated successfully" in {
        val authorizedKeySet = sdksToBeAuthed.map(_.context.sdkVerKey()).map(k => s""""$k"""").mkString(",")
        withChangedConfigFileContent("integration-tests/src/test/resources/common/base.conf",
          """# domain-id-1: ["key1", "key2"]""",
          s"""${adminSdk.context.domainDID}: [$authorizedKeySet]""", {
            reloadConfig(apps(verity1))
            checkConfig(apps(verity1),
              s"$AGENT_AUTHENTICATION_KEYS.${adminSdk.context.domainDID}",
              s"""[$authorizedKeySet]"""
            )
          }
        )
      }
    }
  }

  def connectingInteraction(apps: ScenarioAppEnvironment, sdk: VeritySdkProvider, message: String)(implicit scenario: Scenario): Unit = {
    val inviter = apps(verity1)
    val invitee = apps(cas1)
    val msgReceiverSdk = InteractiveSdkFlow.receivingSdk(apps(verity1).sdks.headOption)
    message + s" (${sdk.sdkConfig.name})" - {
      val connectionId = UUID.randomUUID().toString
      connect_1_0(
        inviter.name,
        sdk,
        msgReceiverSdk,
        invitee.name,
        receivingSdk(invitee),
        connectionId,
        "connection"
      )
    }
  }
}
