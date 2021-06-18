package com.evernym.verity.integration.with_basic_sdk

import com.evernym.verity.agentmsg.msgfamily.ConfigDetail
import com.evernym.verity.agentmsg.msgfamily.configs.UpdateConfigReqMsg
import com.evernym.verity.integration.base.{CAS, VAS, VerityProviderBaseSpec}
import com.evernym.verity.integration.base.sdk_provider.SdkProvider


class AgentProvisioningSpec
  extends VerityProviderBaseSpec
    with SdkProvider {

  lazy val issuerVerityEnv = VerityEnvBuilder.default().build(VAS)
  lazy val holderVerityEnv = VerityEnvBuilder.default().build(CAS)

  lazy val issuerSDK = setupIssuerSdk(issuerVerityEnv)
  lazy val holderSDK = setupHolderSdk(holderVerityEnv, defaultSvcParam.ledgerTxnExecutor)


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
        val comMethodUpdated = issuerSDK.registerWebhook()
        comMethodUpdated.id.nonEmpty shouldBe true
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
}
