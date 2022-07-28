package com.evernym.verity.integration.protocols.issuer_setup

import com.evernym.verity.actor.PublicIdentityStored
import com.evernym.verity.actor.wallet.{CreateDID, NewKeyCreated}
import com.evernym.verity.agentmsg.msgfamily.ConfigDetail
import com.evernym.verity.agentmsg.msgfamily.configs.UpdateConfigReqMsg
import com.evernym.verity.integration.base.{VAS, VerityProviderBaseSpec}
import com.evernym.verity.integration.base.sdk_provider.SdkProvider
import com.evernym.verity.protocol.engine.asyncapi.wallet.WalletAccess.KEY_ED25519
import com.evernym.verity.protocol.protocols.issuersetup.v_0_7.IssuerSetup
import com.evernym.verity.protocol.protocols.issuersetup.{v_0_6 => issuerSetup_v0_6}
import com.evernym.verity.protocol.protocols.issuersetup.{v_0_7 => issuerSetup_v0_7}
import com.evernym.verity.util.TestExecutionContextProvider
import com.evernym.verity.util2.ExecutionContextProvider

import scala.concurrent.ExecutionContext


//confirms that issuer setup 0.6 is backward compatible with legacy events
class BackwardCompatibilitySpec
  extends VerityProviderBaseSpec
    with SdkProvider {

  lazy val ecp = TestExecutionContextProvider.ecp
  lazy val executionContext: ExecutionContext = ecp.futureExecutionContext

  lazy val issuerVerityEnv = VerityEnvBuilder.default().build(VAS)
  lazy val issuerSDK = setupIssuerSdk(issuerVerityEnv, executionContext)

  var issuerKey: NewKeyCreated = null

  override def beforeAll(): Unit = {
    super.beforeAll()
    issuerSDK.fetchAgencyKey()
    issuerSDK.provisionVerityEdgeAgent()
    issuerSDK.registerWebhook()
    issuerSDK.sendUpdateConfig(UpdateConfigReqMsg(Set(ConfigDetail("name", "issuer-name"), ConfigDetail("logoUrl", "issuer-logo-url"))))
  }

  "IssuerSetup" - {
    "when executed issuer setup 0.6 with legacy events" - {
      "should be successful" in {
        issuerKey = performIssuerSetup0_6()
      }
    }

    "when asked to get current public identifier 0.6" - {
      "should be successful" in {
        issuerSDK.sendMsg(issuerSetup_v0_6.CurrentPublicIdentifier())
        val publicIdentifier = issuerSDK.expectMsgOnWebhook[issuerSetup_v0_6.PublicIdentifier]().msg
        publicIdentifier.did.split(":").last shouldBe issuerKey.did
        publicIdentifier.verKey shouldBe issuerKey.verKey
      }
    }

    "when asked to get current public identifier 0.7" - {
      "should be successful" in {
        issuerSDK.sendMsg(issuerSetup_v0_7.CurrentPublicIdentifier())
        val problemReport = issuerSDK.expectMsgOnWebhook[issuerSetup_v0_7.ProblemReport]().msg
        problemReport.message.startsWith(IssuerSetup.identifierAlreadyCreatedErrorMsg) shouldBe true
      }
    }
  }


  /**
   * persists legacy events for issuer setup 0.6
   */
  private def performIssuerSetup0_6(): NewKeyCreated = {
    val domainId = issuerSDK.verityAgentDidPairOpt.get.did
    val agentActorEntityId = getAgentRoute(issuerVerityEnv, domainId).address
    val newDID = performWalletOp[NewKeyCreated](
      issuerVerityEnv,
      agentActorEntityId,
      CreateDID(KEY_ED25519, None),
    )
    persistProtocolEvents(
      issuerVerityEnv,
      issuerSetup_v0_6.IssuerSetupDefinition,
      domainId,
      None,
      None,
      Seq(
        issuerSetup_v0_6.RosterInitialized(domainId),
        issuerSetup_v0_6.CreatePublicIdentifierCompleted(newDID.did, newDID.verKey)
      )
    )
    persistUserAgentEvents(
      issuerVerityEnv,
      agentActorEntityId,
      Seq(
        PublicIdentityStored(newDID.did, newDID.verKey)
      )
    )
    newDID
  }

  /**
   * custom thread pool executor
   */
  override def futureExecutionContext: ExecutionContext = executionContext

  override def executionContextProvider: ExecutionContextProvider = ecp
}
