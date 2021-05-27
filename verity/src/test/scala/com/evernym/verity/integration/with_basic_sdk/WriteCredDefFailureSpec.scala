package com.evernym.verity.integration.with_basic_sdk

import com.evernym.verity.actor.testkit.actor.MockLedgerTxnExecutor
import com.evernym.verity.agentmsg.msgfamily.ConfigDetail
import com.evernym.verity.agentmsg.msgfamily.configs.UpdateConfigReqMsg
import com.evernym.verity.integration.base.VerityProviderBaseSpec
import com.evernym.verity.integration.base.sdk_provider.SdkProvider
import com.evernym.verity.integration.base.verity_provider.{LedgerSvcParam, ServiceParam}
import com.evernym.verity.ledger.{LedgerSvcException, TxnResp}
import com.evernym.verity.protocol.engine.asyncapi.wallet.WalletAccess
import com.evernym.verity.protocol.protocols.issuersetup.v_0_6._
import com.evernym.verity.protocol.protocols.writeSchema.v_0_6.{Write => WriteSchema, StatusReport => WriteSchemaStatusReport}
import com.evernym.verity.protocol.protocols.writeCredentialDefinition.v_0_6.{Write => WriteCredDef}

import scala.concurrent.Future

class WriteCredDefFailureSpec
  extends VerityProviderBaseSpec
    with SdkProvider  {

  override val defaultSvcParam: ServiceParam = ServiceParam(LedgerSvcParam(ledgerTxnExecutor = new DummyLedgerTxnExecutor()))

  lazy val issuerVerityApp = setupNewVerityEnv()
  lazy val issuerSDK = setupIssuerSdk(issuerVerityApp)
  var schemaId: String = ""

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
    issuerSDK.sendMsg(WriteSchema("name", "1.0", Seq("name", "age")))
    val sr = issuerSDK.expectMsgOnWebhook[WriteSchemaStatusReport]()
    schemaId = sr.msg.schemaId
  }

  "IssuerSdk" - {

    "when sent 'write' (write-cred-def 0.6) message and ledger returns error" - {
      "should receive 'status-report'" in {
        issuerSDK.sendMsg(WriteCredDef("name", schemaId, None, None))
        val receivedMsg = issuerSDK.expectMsgOnWebhook[ProblemReport]()
        receivedMsg.msg.message.contains("invalid TAA") shouldBe true
      }
    }
  }

  class DummyLedgerTxnExecutor() extends MockLedgerTxnExecutor {

    // mimicking the scenario where 'writeCredDef' fails to
    // check how it is handled by protocol
    override def writeCredDef(submitterDID: DID,
                              schemaJson: String,
                              walletAccess: WalletAccess): Future[TxnResp] = {
      Future.failed(LedgerSvcException("invalid TAA"))
    }
  }
}
