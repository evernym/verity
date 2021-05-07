package com.evernym.verity.integration.with_basic_sdk

import com.evernym.verity.agentmsg.msgfamily.ConfigDetail
import com.evernym.verity.agentmsg.msgfamily.configs.UpdateConfigReqMsg
import com.evernym.verity.app_launcher.HttpServer
import com.evernym.verity.integration.base.VerityAppBaseSpec
import com.evernym.verity.integration.base.sdk_provider.SdkProvider


class AgentProvisioningSpec
  extends VerityAppBaseSpec
    with SdkProvider {

  lazy val verityVAS = setupNewVerityApp()
  lazy val verityCAS = setupNewVerityApp()

  lazy val issuerSDK = setupIssuerSdk(verityVAS.platform)
  lazy val holderSDK = setupHolderSdk(verityCAS.platform)

  override lazy val allVerityApps: List[HttpServer] = List(verityVAS, verityCAS)

  "IssuerSDK" - {

    "when tried to fetch CAS agency agent keys" - {
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
        val comMethodUpdated = issuerSDK.registerWebhook()
        comMethodUpdated.id.nonEmpty shouldBe true
      }
    }

    "when tried to update config" - {
      "should be successful" in {
        val configResult = issuerSDK.sendUpdateConfig(
          UpdateConfigReqMsg(Set(ConfigDetail("name", "issuer-name"), ConfigDetail("logoUrl", "issuer-logo-url")))
        )
        configResult.configs.size shouldBe 2
      }
    }

  }

  "HolderSDK" - {

    "when tried to fetch VAS agency agent keys" - {
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
}
