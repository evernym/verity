package com.evernym.verity.integration.relationship

import com.evernym.verity.agentmsg.msgcodec.jackson.JacksonMsgCodec
import com.evernym.verity.agentmsg.msgfamily.ConfigDetail
import com.evernym.verity.agentmsg.msgfamily.configs.UpdateConfigReqMsg
import com.evernym.verity.did.didcomm.v1.{Thread => MsgThread}
import com.evernym.verity.integration.base.sdk_provider.{IssuerSdk, SdkProvider, V1OAuthParam}
import com.evernym.verity.integration.base.verity_provider.VerityEnv
import com.evernym.verity.integration.base.{VAS, VerityProviderBaseSpec}
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.Ctl.Offer
import com.evernym.verity.protocol.protocols.relationship.v_1_0.Signal.Invitation
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.Sig.{Invitation => IssueCredInvitation}
import com.evernym.verity.protocol.protocols.outofband.v_1_0.Msg.OutOfBandInvitation
import com.evernym.verity.protocol.protocols.writeCredentialDefinition.{v_0_6 => writeCredDef0_6}
import com.evernym.verity.protocol.protocols.writeSchema.{v_0_6 => writeSchema0_6}
import com.evernym.verity.util.Base64Util
import org.json.JSONObject

import scala.concurrent.duration._
import scala.concurrent.Await


class InvitationSpec
  extends VerityProviderBaseSpec
    with SdkProvider {

  var issuerSDK: IssuerSdk = _
  var issuerVerityEnv: VerityEnv = _
  var schemaId: SchemaId = _
  var credDefId: CredDefId = _

  override def beforeAll(): Unit = {
    super.beforeAll()

    val issuerVerityEnvFut = VerityEnvBuilder().buildAsync(VAS)
    val issuerSDKFut = setupIssuerSdkAsync(issuerVerityEnvFut, executionContext, Option(V1OAuthParam(5.seconds)))
    issuerVerityEnv = Await.result(issuerVerityEnvFut, ENV_BUILD_TIMEOUT)
    issuerSDK = Await.result(issuerSDKFut, SDK_BUILD_TIMEOUT)

    issuerSDK.resetPlainMsgsCounter.plainMsgsBeforeLastReset shouldBe 0
    issuerSDK.fetchAgencyKey()
    issuerSDK.provisionVerityEdgeAgent()
    issuerSDK.registerWebhookWithoutOAuth()
    issuerSDK.registerWebhook()
    issuerSDK.sendUpdateConfig(UpdateConfigReqMsg(Set(ConfigDetail("name", "config-issuer-name"), ConfigDetail("logoUrl", "config-issuer-logo-url"))))

    setupIssuer_v0_6(issuerSDK)
    schemaId = writeSchema_v0_6(issuerSDK, writeSchema0_6.Write("name", "1.0", Seq("name", "age")))
    credDefId = writeCredDef_v0_6(issuerSDK, writeCredDef0_6.Write("name", schemaId, None, None))
  }

  "IssuerSDK" - {
    "relationship without name and logo url" - {
      "when tried to send 'connection-invitation' (relationship 1.0) message" - {
        "should be successful" in {
          val connId = "connId1a"
          val receivedMsg = issuerSDK.sendCreateRelationship(connId, None)
          val connInvitation = issuerSDK.sendCreateConnectionInvitation(connId, receivedMsg.threadOpt)
          val inviter = getInviter(connInvitation)
          inviter.label shouldBe "config-issuer-name"
          inviter.logoUrl shouldBe "config-issuer-logo-url"
        }
      }

      "when tried to send 'out-of-band-invitation' (relationship 1.0) message" - {
        "should be successful" in {
          val connId = "connId1b"
          val receivedMsg = issuerSDK.sendCreateRelationship(connId, None)
          val oobInvitation = issuerSDK.sendCreateOOBInvitation(connId, receivedMsg.threadOpt)
          val inviter = getInviter(oobInvitation)
          inviter.label shouldBe "config-issuer-name"
          inviter.logoUrl shouldBe "config-issuer-logo-url"
        }
      }

      "when created OOB with cred offer attachment" - {
        "should be successful" in {
          val connId = "connId1c"
          issuerSDK.sendCreateRelationship(connId)
          val offerMsg = Offer(
            credDefId,
            Map("name" -> "Alice", "age" -> "20"),
            by_invitation = Option(true)
          )
          issuerSDK.sendMsgForConn(connId, offerMsg)
          val invitation = issuerSDK.expectMsgOnWebhook[IssueCredInvitation]().msg
          val oobValue = invitation.inviteURL.split("\\?oob=").last
          val oobIssueCredInvitation = JacksonMsgCodec.fromJson[OutOfBandInvitation](new String(Base64Util.getBase64UrlDecoded(oobValue)))
          oobIssueCredInvitation.label shouldBe "config-issuer-name"
          oobIssueCredInvitation.profileUrl shouldBe Option("config-issuer-logo-url")
        }
      }
    }

    "relationship with name and logo url" - {
      "when tried to send 'connection-invitation' (relationship 1.0) message" - {
        "should be successful" in {
          val connId = "connId2a"
          val receivedMsg = issuerSDK.sendCreateRelationship(connId, Option("rel-issuer-name"), Option("rel-profile-url"))
          val connInvitation = issuerSDK.sendCreateConnectionInvitation(connId, receivedMsg.threadOpt)
          val inviter = getInviter(connInvitation)
          inviter.label shouldBe "rel-issuer-name"
          inviter.logoUrl shouldBe "rel-profile-url"
        }
      }

      "when tried to send 'out-of-band-invitation' (relationship 1.0) message" - {
        "should be successful" in {
          val connId = "connId2b"
          val receivedMsg = issuerSDK.sendCreateRelationship(connId, Option("rel-issuer-name"), Option("rel-profile-url"))
          val oobInvitation = issuerSDK.sendCreateOOBInvitation(connId, receivedMsg.threadOpt)
          val inviter = getInviter(oobInvitation)
          inviter.label shouldBe "rel-issuer-name"
          inviter.logoUrl shouldBe "rel-profile-url"
        }
      }

      "when created OOB with cred offer attachment" - {
        "should be successful" in {
          val connId = "connId2c"
          issuerSDK.sendCreateRelationship(connId, Option("rel-issuer-name"), Option("rel-profile-url"))
          val offerMsg = Offer(
            credDefId,
            Map("name" -> "Alice", "age" -> "20"),
            by_invitation = Option(true)
          )
          issuerSDK.sendMsgForConn(connId, offerMsg)
          val invitation = issuerSDK.expectMsgOnWebhook[IssueCredInvitation]().msg
          val oobValue = invitation.inviteURL.split("\\?oob=").last
          val oobIssueCredInvitation = JacksonMsgCodec.fromJson[OutOfBandInvitation](new String(Base64Util.getBase64UrlDecoded(oobValue)))
          oobIssueCredInvitation.label shouldBe "rel-issuer-name"
          oobIssueCredInvitation.profileUrl shouldBe Option("rel-profile-url")
        }
      }
    }
  }

  private def getInviter(invitation: Invitation): Inviter = {
    if (invitation.inviteURL.contains("c_i=")) {
      invitation.inviteURL.split("c_i=").lastOption.map { ciVal =>
        val ciValue = Base64Util.urlDecodeToStr(ciVal)
        val ciJson = new JSONObject(ciValue)
        val label = ciJson.getString("label")
        val logoUrl = ciJson.getString("profileUrl")
        Inviter(label, logoUrl)
      }.getOrElse(throw new RuntimeException("inviter detail not found"))
    } else if (invitation.inviteURL.contains("oob=")) {
      invitation.inviteURL.split("\\?oob=").lastOption.map { oobVal =>
        val oobValue = new String(Base64Util.getBase64UrlDecoded(oobVal))
        val oobJson = new JSONObject(oobValue)
        val label = oobJson.getString("label")
        val logoUrl = oobJson.getString("profileUrl")
        Inviter(label, logoUrl)
      }.getOrElse(throw new RuntimeException("inviter detail not found"))
    } else {
      throw new RuntimeException("inviter detail not found")
    }
  }

  case class Inviter(label: String, logoUrl: String)

  var lastReceivedMsgThread: Option[MsgThread] = None

}