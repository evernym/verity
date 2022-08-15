package com.evernym.verity.integration.with_basic_sdk

import akka.actor.ActorSystem
import com.evernym.verity.actor.testkit.actor.ActorSystemVanilla
import com.evernym.verity.agentmsg.msgfamily.ConfigDetail
import com.evernym.verity.agentmsg.msgfamily.configs.UpdateConfigReqMsg
import com.evernym.verity.integration.base.sdk_provider.{IssuerSdk, SdkProvider}
import com.evernym.verity.integration.base.verity_provider.VerityEnv
import com.evernym.verity.integration.base.{VAS, VerityProviderBaseSpec}
import com.evernym.verity.testkit.TestSponsor
import com.evernym.verity.util.TimeUtil
import com.typesafe.config.ConfigFactory

import java.util.UUID
import scala.concurrent.Await


class TokenizedAgentProvisioningSpec
  extends VerityProviderBaseSpec
    with SdkProvider {

  var issuerVerityEnv: VerityEnv = _
  val TOTAL_AGENTS_TO_PROVISION = 3

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    val issuerVerityEnvFut = VerityEnvBuilder(nodeCount = 3).withConfig(VAS_CONFIG).buildAsync(VAS)
    issuerVerityEnv = Await.result(issuerVerityEnvFut, ENV_BUILD_TIMEOUT)
  }

  "AgentProvisioning" - {
    "when tried for multiple users" - {
      "should be successful" in {
        (1 to TOTAL_AGENTS_TO_PROVISION).foreach { _ =>
          val sdk = setupIssuerSdk(issuerVerityEnv, executionContext)
          testAgentProvisioning(
            sdk,
            "sponseeId",
            "sponserId",
            UUID.randomUUID().toString,
            TimeUtil.nowDateString,
            testSponsor
          )
        }
      }
    }
  }

  private def testAgentProvisioning(issuerSDK: IssuerSdk,
                                    sponseeId: String,
                                    sponsorId: String,
                                    nonce: String,
                                    timestamp: String,
                                    testSponsor: TestSponsor): Unit = {
    issuerSDK.fetchAgencyKey()
    issuerSDK.agencyPublicDidOpt.isDefined shouldBe true

    val provToken = issuerSDK.buildProvToken(
      sponseeId,
      sponsorId,
      nonce,
      timestamp,
      testSponsor
    )
    val created = issuerSDK.provisionVerityEdgeAgent(provToken)
    created.selfDID.nonEmpty shouldBe true
    created.agentVerKey.nonEmpty shouldBe true

    issuerSDK.registerWebhook()

    val configResult = issuerSDK.sendUpdateConfig(
      UpdateConfigReqMsg(Set(ConfigDetail("name", "issuer-name"), ConfigDetail("logoUrl", "issuer-logo-url")))
    )
    configResult.configs.size shouldBe 2
  }
  val system: ActorSystem = ActorSystemVanilla(UUID.randomUUID().toString)

  val testSponsor = new TestSponsor("000000000000000000000000Trustee1", futureExecutionContext, system)

  val VAS_CONFIG = ConfigFactory parseString {
    s"""
      verity.provisioning {
        sponsors = [
          {
            name = "sponserId"
            id = "sponserId"
            keys = [{"verKey": "${testSponsor.verKey}"}]
            endpoint = "localhost:3456/json-msg"
            active = true
          }
        ]
        sponsor-required = true
        token-window = 10 minute
        cache-used-tokens = true
    }
    """
  }
}
