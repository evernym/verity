package com.evernym.verity.integration.with_basic_sdk

import com.evernym.verity.actor.testkit.actor.MockLedgerTxnExecutor
import com.evernym.verity.agentmsg.msgfamily.ConfigDetail
import com.evernym.verity.agentmsg.msgfamily.configs.UpdateConfigReqMsg
import com.evernym.verity.integration.base.VerityProviderBaseSpec
import com.evernym.verity.integration.base.sdk_provider.SdkProvider
import com.evernym.verity.integration.base.verity_provider.node.local.ServiceParam
import com.evernym.verity.ledger.{LedgerSvcException, TxnResp}
import com.evernym.verity.protocol.engine.asyncapi.wallet.WalletAccess
import com.evernym.verity.protocol.protocols.issuersetup.v_0_6.{Create, CurrentPublicIdentifier, ProblemReport, PublicIdentifier, PublicIdentifierCreated}
import com.evernym.verity.protocol.protocols.writeSchema.v_0_6.Write

import scala.concurrent.Future


class WriteSchemaFailureSpec
  extends VerityProviderBaseSpec
    with SdkProvider  {

  override lazy val defaultSvcParam: ServiceParam = ServiceParam.empty.withLedgerTxnExecutor(new DummyLedgerTxnExecutor())

  lazy val issuerVerityApp = VerityEnvBuilder.default().build()
  lazy val issuerSDK = setupIssuerSdk(issuerVerityApp)

  override def beforeAll(): Unit = {
    super.beforeAll()

    issuerSDK.fetchAgencyKey()
    issuerSDK.provisionVerityEdgeAgent()
    issuerSDK.registerWebhook()
    issuerSDK.sendUpdateConfig(UpdateConfigReqMsg(Set(ConfigDetail("name", "issuer-name"), ConfigDetail("logoUrl", "issuer-logo-url"))))
    issuerSDK.sendMsg(Create())
    issuerSDK.expectMsgOnWebhook[PublicIdentifierCreated]()
    issuerSDK.sendMsg(CurrentPublicIdentifier())
    issuerSDK.expectMsgOnWebhook[PublicIdentifier]()
  }

  "IssuerSdk" - {

    "when sent 'write' (write-schema 0.6) message and ledger returns error" - {
      "should receive 'problem-report'" in {
        issuerSDK.sendMsg(Write("name", "1.0", Seq("name", "age")))
        val receivedMsg = issuerSDK.expectMsgOnWebhook[ProblemReport]()
        receivedMsg.msg.message.contains("invalid TAA") shouldBe true
      }
    }
  }

  class DummyLedgerTxnExecutor() extends MockLedgerTxnExecutor {

    // mimicking the scenario where 'writeSchema' fails to
    // check how it is handled by protocol
    override def writeSchema(submitterDID: DID,
                             schemaJson: String,
                             walletAccess: WalletAccess): Future[TxnResp] = {
      Future.failed(LedgerSvcException("invalid TAA"))
    }
  }
}