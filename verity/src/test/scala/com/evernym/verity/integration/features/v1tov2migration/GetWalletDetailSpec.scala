package com.evernym.verity.integration.features.v1tov2migration

import com.evernym.verity.actor.agent.user.GetWalletMigrationDetailResp
import com.evernym.verity.integration.base.sdk_provider.SdkProvider
import com.evernym.verity.integration.base.{VAS, VerityProviderBaseSpec}
import com.evernym.verity.testkit.util.HttpUtil


class GetWalletDetailSpec
  extends VerityProviderBaseSpec
    with SdkProvider {

  lazy val issuerVerity = VerityEnvBuilder().build(VAS)
  lazy val issuerSDK = setupIssuerSdk(issuerVerity, executionContext)


  override def beforeAll(): Unit = {
    super.beforeAll()

    issuerSDK.fetchAgencyKey()
    issuerSDK.provisionVerityEdgeAgent()
    issuerSDK.registerWebhookWithoutOAuth()
  }

  "IssuerSDK" - {
    "when tried to send 'GET_UPGRADE_INFO' (v1tov2migration 1.0) message" - {
      "should be successful" in {
        val apiUrl = issuerSDK.buildFullUrl(s"agency/internal/maintenance/v1tov2migration/" +
          s"VAS/agent/${issuerSDK.verityAgentDidPair.did}/walletMigrationDetail")
        val apiResp = HttpUtil.sendGET(apiUrl)
        val resp = HttpUtil.parseHttpResponseAs[GetWalletMigrationDetailResp](apiResp)
        resp.config.getString("storage_type") shouldBe "default"
        resp.credential.getString("key_derivation_method") shouldBe "RAW"
        resp.credential.getJSONObject("storage_credentials").toString() shouldBe """{}"""
      }
    }
  }
}
