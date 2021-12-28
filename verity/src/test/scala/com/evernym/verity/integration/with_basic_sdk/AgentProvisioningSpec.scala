package com.evernym.verity.integration.with_basic_sdk

import com.evernym.verity.agentmsg.msgfamily.ConfigDetail
import com.evernym.verity.agentmsg.msgfamily.configs.UpdateConfigReqMsg
import com.evernym.verity.integration.base.{CAS, VAS, VerityProviderBaseSpec}
import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.integration.base.sdk_provider.{HolderSdk, IssuerSdk, SdkProvider}
import com.evernym.verity.util.TestExecutionContextProvider

import scala.concurrent.{Await, ExecutionContext}


class AgentProvisioningSpec
  extends VerityProviderBaseSpec
    with SdkProvider {

  lazy val ecp = TestExecutionContextProvider.ecp
  lazy val executionContext: ExecutionContext = ecp.futureExecutionContext

  lazy val issuerVerityEnv = VerityEnvBuilder.default().buildAsync(VAS)
  lazy val holderVerityEnv = VerityEnvBuilder.default().buildAsync(CAS)

  lazy val issuerSDKFut = setupIssuerSdkAsync(issuerVerityEnv, executionContext)
  lazy val holderSDKFut = setupHolderSdkAsync(holderVerityEnv, defaultSvcParam.ledgerTxnExecutor, executionContext)

  var issuerSDK: IssuerSdk = _
  var holderSDK: HolderSdk = _

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    val f1 = issuerSDKFut
    val f2 = holderSDKFut

    issuerSDK = Await.result(f1, SDK_BUILD_TIMEOUT)
    holderSDK = Await.result(f2, SDK_BUILD_TIMEOUT)

  }


  "IssuerSDK" - {

    "when tried to fetch agency agent keys" - {
      "should be successful" in {
        issuerSDK.fetchAgencyKey()
        issuerSDK.agencyPublicDidOpt.isDefined shouldBe true
      }
    }

    "when tried to provision verity agent" - {
      "should be successful" in {
        val created = issuerSDK.provisionVerityEdgeAgent()
        created.selfDID.nonEmpty shouldBe true
        created.agentVerKey.nonEmpty shouldBe true
      }
    }

    "when tried to register a webhook" - {
      "should be successful" in {
        issuerSDK.registerWebhook()
      }
    }

    "when sent update (update-config 0.6) message" - {
      "should be successful" in {
        val configResult = issuerSDK.sendUpdateConfig(
          UpdateConfigReqMsg(Set(ConfigDetail("name", "issuer-name"), ConfigDetail("logoUrl", "issuer-logo-url")))
        )
        configResult.configs.size shouldBe 2
      }
    }

  }

  "HolderSDK" - {

    "when tried to fetch agency agent keys" - {
      "should be successful" in {
        holderSDK.fetchAgencyKey()
        holderSDK.agencyPublicDidOpt.isDefined shouldBe true
      }
    }

    "when tried to provision verity agent" - {
      "should be successful" in {
        val created = holderSDK.provisionVerityCloudAgent()
        created.selfDID.nonEmpty shouldBe true
        created.agentVerKey.nonEmpty shouldBe true
      }
    }
  }

  /**
   * custom thread pool executor
   */
  override def futureExecutionContext: ExecutionContext = executionContext

  override def executionContextProvider: ExecutionContextProvider = ecp
}
