package com.evernym.verity.integration.with_basic_sdk.out_of_band.with_attachment

import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.actor.agent.{Thread => MsgThread}
import com.evernym.verity.agentmsg.msgcodec.jackson.JacksonMsgCodec
import com.evernym.verity.integration.base.sdk_provider.SdkProvider
import com.evernym.verity.integration.base.{CAS, VAS, VerityProviderBaseSpec}
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.Ctl.Offer
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.Msg.OfferCred
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.Sig.{Invitation => IssueCredInvitation}
import com.evernym.verity.protocol.protocols.outofband.v_1_0.Msg.OutOfBandInvitation
import com.evernym.verity.protocol.protocols.relationship.v_1_0.Signal.Invitation
import com.evernym.verity.protocol.protocols.writeCredentialDefinition.{v_0_6 => writeCredDef0_6}
import com.evernym.verity.protocol.protocols.writeSchema.{v_0_6 => writeSchema0_6}
import com.evernym.verity.util.{Base64Util, TestExecutionContextProvider}

import scala.concurrent.ExecutionContext


//Holder1 receives a "cred offer attached OOB invitation" from an Issuer and accepts it.
//Then Holder2 tries to accept same "cred offer attached OOB invitation", it should fail
// (as Holder1 has already accepted that invitation)

class ReuseInvitationSpec
  extends VerityProviderBaseSpec
    with SdkProvider {
  lazy val ecp = TestExecutionContextProvider.ecp
  lazy val executionContext: ExecutionContext = ecp.futureExecutionContext
  lazy val issuerVerityEnv = VerityEnvBuilder.default().build(VAS)
  lazy val holderVerityEnv = VerityEnvBuilder.default().build(CAS)

  lazy val issuerSDK = setupIssuerSdk(issuerVerityEnv, executionContext, ecp.walletFutureExecutionContext)
  lazy val holderSDK1 = setupHolderSdk(holderVerityEnv, defaultSvcParam.ledgerTxnExecutor, executionContext, ecp.walletFutureExecutionContext)
  lazy val holderSDK2 = setupHolderSdk(holderVerityEnv, defaultSvcParam.ledgerTxnExecutor, executionContext, ecp.walletFutureExecutionContext)

  val oobIssuerHolderConn1 = "connId1"
  val oobIssuerHolderConn2 = "connId2"

  var schemaId: SchemaId = _
  var credDefId: CredDefId = _
  var offerCred: OfferCred = _

  var lastReceivedThread: Option[MsgThread] = None
  var issueCredInvitation: Option[IssueCredInvitation] = None
  var oobIssueCredInvitation: Option[OutOfBandInvitation] = None

  override def beforeAll(): Unit = {
    super.beforeAll()
    provisionEdgeAgent(issuerSDK)
    provisionCloudAgent(holderSDK1)
    provisionCloudAgent(holderSDK2)

    setupIssuer(issuerSDK)
    schemaId = writeSchema(issuerSDK, writeSchema0_6.Write("name", "1.0", Seq("name", "age")))
    credDefId = writeCredDef(issuerSDK, writeCredDef0_6.Write("name", schemaId, None, None))
  }

  "IssuerSDK creating first OOB cred offer" - {
    "when created new relationship" - {
      "should be successful" in {
        val receivedMsg = issuerSDK.sendCreateRelationship(oobIssuerHolderConn1)
        lastReceivedThread = receivedMsg.threadOpt
      }
    }

    "sends 'offer' (issue-credential 1.0) via oob invitation" - {
      "should be successful" in {
        val offerMsg = Offer(
          credDefId,
          Map("name" -> "Alice", "age" -> "20"),
          by_invitation = Option(true)
        )
        issuerSDK.sendMsgForConn(oobIssuerHolderConn1, offerMsg)
        val invitation = issuerSDK.expectMsgOnWebhook[IssueCredInvitation]().msg
        val oobValue = invitation.inviteURL.split("\\?oob=").last
        issueCredInvitation = Option(invitation)
        oobIssueCredInvitation = Option(JacksonMsgCodec.fromJson[OutOfBandInvitation](new String(Base64Util.getBase64UrlDecoded(oobValue))))
      }
    }
  }

  "HolderSDK1" - {
    "as there is no previous connection with the issuer" - {
      "when tried to accept the OOB invitation first time" - {
        "should be successful" in {
          holderSDK1.sendCreateNewKey(oobIssuerHolderConn1)
          val issueCredInvite = issueCredInvitation.get
          val relInvite = Invitation(issueCredInvite.inviteURL, issueCredInvite.shortInviteURL, issueCredInvite.invitationId)
          holderSDK1.sendConnReqForInvitation(oobIssuerHolderConn1, relInvite)
          issuerSDK.expectConnectionComplete(oobIssuerHolderConn1)
        }
      }
    }
  }

  "HolderSDK2" - {
    "as there is no previous connection with the issuer" - {
      "when tried to accept the same OOB invitation again" - {
        "should fail because that OOB is already accepted by another holder" in {
          holderSDK2.sendCreateNewKey(oobIssuerHolderConn2)
          val issueCredInvite = issueCredInvitation.get
          val relInvite = Invitation(issueCredInvite.inviteURL, issueCredInvite.shortInviteURL, issueCredInvite.invitationId)
          val ex = intercept[IllegalArgumentException] {
            holderSDK2.sendConnReqForInvitation(oobIssuerHolderConn2, relInvite)
          }
          ex.getMessage.contains("unauthorized")
        }
      }
    }
  }

  /**
   * custom thread pool executor
   */
  override def futureExecutionContext: ExecutionContext = executionContext

  override def executionContextProvider: ExecutionContextProvider = ecp
}