package com.evernym.verity.integration.protocols.issue_credential.v1_0

import com.evernym.verity.agentmsg.msgcodec.jackson.JacksonMsgCodec
import com.evernym.verity.did.didcomm.v1.messages.ProblemDescription
import com.evernym.verity.did.didcomm.v1.{Thread => MsgThread}
import com.evernym.verity.integration.base.sdk_provider.{HolderSdk, IssuerSdk, SdkProvider}
import com.evernym.verity.integration.base.verity_provider.VerityEnv
import com.evernym.verity.integration.base.{CAS, VAS, VerityProviderBaseSpec}
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.Ctl.Offer
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.Msg
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.Sig.{ProblemReport, Invitation => IssueCredInvitation}
import com.evernym.verity.protocol.protocols.outofband.v_1_0.Msg.OutOfBandInvitation
import com.evernym.verity.protocol.protocols.relationship.v_1_0.Signal.Invitation
import com.evernym.verity.protocol.protocols.writeCredentialDefinition.{v_0_6 => writeCredDef0_6}
import com.evernym.verity.protocol.protocols.writeSchema.{v_0_6 => writeSchema0_6}
import com.evernym.verity.util.Base64Util
import org.json.JSONObject

import scala.concurrent.Await


class IssueCredRejectionSpec
  extends VerityProviderBaseSpec
    with SdkProvider {

  var issuerVerityEnv: VerityEnv = _

  var issuerSDK: IssuerSdk = _
  var holderSDK: HolderSdk = _

  val oobIssuerHolderConn = "connId1"

  var schemaId: SchemaId = _
  var credDefId: CredDefId = _

  var lastReceivedThread: Option[MsgThread] = None
  var issueCredInvitation: Option[IssueCredInvitation] = None
  var oobIssueCredInvitation: Option[OutOfBandInvitation] = None

  override def beforeAll(): Unit = {
    super.beforeAll()

    val issuerVerityEnvFut = VerityEnvBuilder().buildAsync(VAS)
    val holderVerityEnvFut = VerityEnvBuilder().buildAsync(CAS)

    val issuerSDKFut = setupIssuerSdkAsync(issuerVerityEnvFut, executionContext)
    val holderSDKFut = setupHolderSdkAsync(holderVerityEnvFut, defaultSvcParam.ledgerTxnExecutor, defaultSvcParam.vdrTools, executionContext)

    issuerVerityEnv = Await.result(issuerVerityEnvFut, ENV_BUILD_TIMEOUT)

    issuerSDK = Await.result(issuerSDKFut, SDK_BUILD_TIMEOUT)
    holderSDK = Await.result(holderSDKFut, SDK_BUILD_TIMEOUT)

    provisionEdgeAgent(issuerSDK)
    provisionCloudAgent(holderSDK)
    setupIssuer_v0_6(issuerSDK)
    schemaId = writeSchema_v0_6(issuerSDK, writeSchema0_6.Write("name", "1.0", Seq("name", "age")))
    credDefId = writeCredDef_v0_6(issuerSDK, writeCredDef0_6.Write("name", schemaId, None, None))
  }

  "IssuerSDK creating first OOB cred offer" - {
    "when created new relationship" - {
      "should be successful" in {
        val receivedMsg = issuerSDK.sendCreateRelationship(oobIssuerHolderConn)
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
        issuerSDK.sendMsgForConn(oobIssuerHolderConn, offerMsg)
        val invitation = issuerSDK.expectMsgOnWebhook[IssueCredInvitation]().msg
        val oobValue = invitation.inviteURL.split("\\?oob=").last
        issueCredInvitation = Option(invitation)
        oobIssueCredInvitation = Option(JacksonMsgCodec.fromJson[OutOfBandInvitation](new String(Base64Util.getBase64UrlDecoded(oobValue))))
      }
    }
  }

  "HolderSDK" - {
    "as there is no previous connection with the issuer" - {
      "when tried to accept the OOB invitation first time" - {
        "should be successful" in {
          holderSDK.sendCreateNewKey(oobIssuerHolderConn)
          val issueCredInvite = issueCredInvitation.get
          val relInvite = Invitation(issueCredInvite.inviteURL, issueCredInvite.shortInviteURL, issueCredInvite.invitationId)
          holderSDK.sendConnReqForInvitation(oobIssuerHolderConn, relInvite)
          issuerSDK.expectConnectionComplete(oobIssuerHolderConn)
        }
      }

      "when tried to send rejection via 'problem-report' (issue-credential 1.0) message" - {
        "should be successful" in {
          val oobInvite = oobIssueCredInvitation.get
          val oobOfferCredAttachment = new String(Base64Util.getBase64Decoded(oobInvite.`request~attach`.head.data.base64))
          val attachmentJsonObj = new JSONObject(oobOfferCredAttachment)
          lastReceivedThread = Option(MsgThread(Option(attachmentJsonObj.getJSONObject("~thread").getString("thid"))))
          val rejectionProblemReport = Msg.buildProblemReport("rejecting the credential offer", "rejection")
          holderSDK.sendProtoMsgToTheirAgent(oobIssuerHolderConn, rejectionProblemReport, lastReceivedThread)
        }
      }
    }
  }

  "IssuerSDK" - {
    "should receive problem report" in {
      val problemReport = issuerSDK.expectMsgOnWebhook[ProblemReport]().msg
      problemReport.description shouldBe ProblemDescription(Some("rejecting the credential offer"), "rejection")
    }
  }
}
