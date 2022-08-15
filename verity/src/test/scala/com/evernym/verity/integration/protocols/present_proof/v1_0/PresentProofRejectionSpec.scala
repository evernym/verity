package com.evernym.verity.integration.protocols.present_proof.v1_0

import com.evernym.verity.agentmsg.msgcodec.jackson.JacksonMsgCodec
import com.evernym.verity.did.didcomm.v1.messages.ProblemDescription
import com.evernym.verity.did.didcomm.v1.{Thread => MsgThread}
import com.evernym.verity.integration.base.sdk_provider.{HolderSdk, IssuerSdk, SdkProvider}
import com.evernym.verity.integration.base.verity_provider.VerityEnv
import com.evernym.verity.integration.base.{CAS, VAS, VerityProviderBaseSpec}
import com.evernym.verity.protocol.engine.ThreadId
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.Sig.{ProblemReport, StatusReport, Invitation => PresentProofInvitation}
import com.evernym.verity.protocol.protocols.outofband.v_1_0.Msg.OutOfBandInvitation
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.Ctl.{Request, Status}
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.{Msg, ProofAttribute}
import com.evernym.verity.protocol.protocols.relationship.v_1_0.Signal.Invitation
import com.evernym.verity.util.Base64Util
import org.json.JSONObject

import java.util.UUID
import scala.concurrent.Await


class PresentProofRejectionSpec
  extends VerityProviderBaseSpec
    with SdkProvider {

  var verifierVerityEnv: VerityEnv = _
  var verifierSDK: IssuerSdk = _
  var holderSDK: HolderSdk = _

  val verifierHolderConn = "connId"
  var nonRejectableThreadId: Option[ThreadId] = None

  var presentationReqInvitation: Option[PresentProofInvitation] = None
  var oobPresentationReqInvitation: Option[OutOfBandInvitation] = None

  override def beforeAll(): Unit = {
    super.beforeAll()

    val issuerVerityEnvFut = VerityEnvBuilder().buildAsync(VAS)
    val holderVerityEnvFut = VerityEnvBuilder().buildAsync(CAS)

    val verifierSDKFut = setupIssuerSdkAsync(issuerVerityEnvFut, executionContext)
    val holderSDKFut = setupHolderSdkAsync(holderVerityEnvFut, defaultSvcParam.ledgerTxnExecutor, defaultSvcParam.vdrTools, executionContext)

    verifierVerityEnv = Await.result(issuerVerityEnvFut, ENV_BUILD_TIMEOUT)

    verifierSDK = Await.result(verifierSDKFut, SDK_BUILD_TIMEOUT)
    holderSDK = Await.result(holderSDKFut, SDK_BUILD_TIMEOUT)

    provisionEdgeAgent(verifierSDK)
    provisionCloudAgent(holderSDK)
  }


  "VerifierSDK" - {

    "when created new relationship" - {
      "should be successful" in {
        verifierSDK.sendCreateRelationship(verifierHolderConn)
      }
    }

    "sends 'status' (present-proof 1.0)" - {
      "should be successful" in {
        val msg = Status()
        verifierSDK.sendMsgForConn(verifierHolderConn, msg, Option(MsgThread(thid = Option(UUID.randomUUID().toString))))
        val statusReport = verifierSDK.expectMsgOnWebhook[StatusReport]()
        nonRejectableThreadId = statusReport.threadIdOpt
      }
    }

    "sends 'request' (present-proof 1.0) via oob invitation" - {
      "should be successful" in {
        val msg = Request(
          "name-age",
          Option(List(
            ProofAttribute(
              None,
              Option(List("name", "age")),
              None,
              None,
              self_attest_allowed = false)
          )),
          None,
          None,
          by_invitation = Option(true)
        )
        verifierSDK.sendMsgForConn(verifierHolderConn, msg, Option(MsgThread(thid = Option(UUID.randomUUID().toString))))
        val invitation = verifierSDK.expectMsgOnWebhook[PresentProofInvitation]().msg
        val oobValue = invitation.inviteURL.split("\\?oob=").last
        presentationReqInvitation = Option(invitation)
        oobPresentationReqInvitation = Option(JacksonMsgCodec.fromJson[OutOfBandInvitation](new String(Base64Util.getBase64UrlDecoded(oobValue))))
      }
    }
  }

  "HolderSDK" - {
    "when tried to accept the OOB invitation" - {
      "should be successful" in {
        holderSDK.sendCreateNewKey(verifierHolderConn)
        val presentationReqInvite = presentationReqInvitation.get
        val relInvite = Invitation(presentationReqInvite.inviteURL, presentationReqInvite.shortInviteURL, presentationReqInvite.invitationId)
        holderSDK.sendConnReqForInvitation(verifierHolderConn, relInvite)
        verifierSDK.expectConnectionComplete(verifierHolderConn)
      }
    }
  }

  "HolderSDK" - {
    "when tried to send 'problem-report' (present-proof 1.0) message" - {
      "should be successful" in {
        val rejectionProblemReport = Msg.buildProblemReport("rejecting the presentation offer", "rejection")
        holderSDK.sendProtoMsgToTheirAgent(verifierHolderConn, rejectionProblemReport, Option(MsgThread(thid = nonRejectableThreadId)))
      }
    }
  }

  "VerifierSDK" - {
    "should receive problem report with proper error message" in {
      val problemReport = verifierSDK.expectMsgOnWebhook[ProblemReport]().msg
      problemReport.description shouldBe ProblemDescription(Some("Rejected in un allowed state -- rejecting the presentation offer"), "rejection")
    }
  }

  "HolderSDK" - {
    "when tried to send rejection via 'problem-report' (present-proof 1.0) message" - {
      "should be successful" in {
        val oobInvite = oobPresentationReqInvitation.get
        val oobPresentationReqAttachment = new String(Base64Util.getBase64Decoded(oobInvite.`request~attach`.head.data.base64))
        val attachmentJsonObj = new JSONObject(oobPresentationReqAttachment)
        val lastReceivedThread = Option(MsgThread(Option(attachmentJsonObj.getJSONObject("~thread").getString("thid"))))
        val rejectionProblemReport = Msg.buildProblemReport("rejecting the presentation offer", "rejection")
        holderSDK.sendProtoMsgToTheirAgent(verifierHolderConn, rejectionProblemReport, lastReceivedThread)
      }
    }
  }

  "VerifierSDK" - {
    "should receive problem report for rejection" in {
      val problemReport = verifierSDK.expectMsgOnWebhook[ProblemReport]().msg
      problemReport.description shouldBe ProblemDescription(Some("Rejected -- rejecting the presentation offer"), "rejection")
    }
  }
}