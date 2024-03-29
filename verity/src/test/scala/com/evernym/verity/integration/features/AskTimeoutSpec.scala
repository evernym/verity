package com.evernym.verity.integration.features

import com.evernym.verity.actor.AgentCreated
import com.evernym.verity.actor.testkit.CommonSpecUtil
import com.evernym.verity.integration.base.sdk_provider.SdkProvider
import com.evernym.verity.integration.base.{VAS, VerityProviderBaseSpec}
import com.evernym.verity.protocol.protocols.agentprovisioning.v_0_7.AgentProvisioningMsgFamily.CreateEdgeAgent


class AskTimeoutSpec
  extends VerityProviderBaseSpec
    with SdkProvider {

  lazy val issuerVerityEnv = VerityEnvBuilder().build(VAS)
  lazy val issuerSDK = setupIssuerSdk(issuerVerityEnv, executionContext)

  "IssuerSdk" - {
    "when tried to reproduce ask time out scenario" - {
      "should receive appropriate error" in {
        issuerSDK.fetchAgencyKey()
        val msg = CreateEdgeAgent(issuerSDK.localAgentDidPair.verKey, None)
        val randomDID = CommonSpecUtil.generateNewDid().did
        val ex = intercept[IllegalArgumentException] {
          issuerSDK.sendToRoute[AgentCreated](msg, randomDID)
        }
        ex.getMessage.contains(s"agent not created for route: $randomDID") shouldBe true
      }
    }
  }
}
