package com.evernym.verity.integration.with_basic_sdk

import com.evernym.verity.actor.agent.{Thread => MsgThread}
import com.evernym.verity.agentmsg.msgcodec.jackson.JacksonMsgCodec
import com.evernym.verity.integration.base.VerityProviderBaseSpec
import com.evernym.verity.integration.base.sdk_provider.SdkProvider
import com.evernym.verity.protocol.didcomm.messages.ProblemDescription
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.Ctl.{Issue, Offer}
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.Msg.{IssueCred, OfferCred, ProblemReport => IssueCredProblemReport}
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.Sig.{AcceptRequest, Sent, Invitation => IssueCredInvitation}
import com.evernym.verity.protocol.protocols.outofband.v_1_0.Msg.{HandshakeReuse, HandshakeReuseAccepted, OutOfBandInvitation}
import com.evernym.verity.protocol.protocols.outofband.v_1_0.Signal.ConnectionReused
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.Ctl.Request
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.Msg.{RequestPresentation, ProblemReport => PresentProofProblemReport}
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.ProofAttribute
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.Sig.PresentationResult
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.Sig.{Invitation => ProofReqInvitation}
import com.evernym.verity.protocol.protocols.relationship.v_1_0.Signal.Invitation
import com.evernym.verity.protocol.protocols.writeCredentialDefinition.{v_0_6 => writeCredDef0_6}
import com.evernym.verity.protocol.protocols.writeSchema.{v_0_6 => writeSchema0_6}
import com.evernym.verity.util.Base64Util
import org.json.JSONObject


class AcceptOutOfBandTwiceSpec
  extends VerityProviderBaseSpec
    with SdkProvider {

  lazy val issuerVerityEnv = VerityEnvBuilder.default().build()
  lazy val verifierVerityEnv = VerityEnvBuilder.default().build()
  lazy val holderVerityEnv = VerityEnvBuilder.default().build()

  lazy val issuerSDK = setupIssuerSdk(issuerVerityEnv)
  lazy val verifierSDK = setupVerifierSdk(verifierVerityEnv)
  lazy val holderSDK = setupHolderSdk(holderVerityEnv, defaultSvcParam.ledgerTxnExecutor)

  val oobIssuerHolderConn = "connId1"
  val oobVerifierHolderConn = "connId2"

  var schemaId: SchemaId = _
  var credDefId: CredDefId = _
  var offerCred: OfferCred = _
  var reqPresentation: RequestPresentation = _

  var lastReceivedThread: Option[MsgThread] = None
  var issueCredInvitation: Option[IssueCredInvitation] = None
  var oobIssueCredInvitation: Option[OutOfBandInvitation] = None

  var proofReqInvitation: Option[ProofReqInvitation] = None
  var oobProofReqInvitation: Option[OutOfBandInvitation] = None

  override def beforeAll(): Unit = {
    super.beforeAll()
    provisionEdgeAgent(issuerSDK)
    provisionEdgeAgent(verifierSDK)
    provisionCloudAgent(holderSDK)

    setupIssuer(issuerSDK)
    schemaId = writeSchema(issuerSDK, writeSchema0_6.Write("name", "1.0", Seq("name", "age")))
    credDefId = writeCredDef(issuerSDK, writeCredDef0_6.Write("name", schemaId, None, None))
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

      "when tried to send 'request-credential' (issue-credential 1.0) message" - {
        "should be successful" in {
          val oobInvite = oobIssueCredInvitation.get
          val oobOfferCredAttachment = new String(Base64Util.getBase64Decoded(oobInvite.`request~attach`.head.data.base64))
          val attachmentJsonObj = new JSONObject(oobOfferCredAttachment)
          offerCred = JacksonMsgCodec.fromJson[OfferCred](attachmentJsonObj.toString())
          lastReceivedThread = Option(MsgThread(Option(attachmentJsonObj.getJSONObject("~thread").getString("thid"))))
          holderSDK.sendCredRequest(oobIssuerHolderConn, credDefId, offerCred, lastReceivedThread)
        }
      }
    }
  }

  "IssuerSDK" - {
    "when waiting for message on webhook" - {
      "should get 'accept-request' (issue-credential 1.0)" in {
        issuerSDK.expectMsgOnWebhook[AcceptRequest]()
      }
    }

    "when sent 'issue' (issue-credential 1.0) message" - {
      "should be successful" in {
        val issueMsg = Issue()
        issuerSDK.sendMsgForConn(oobIssuerHolderConn, issueMsg, lastReceivedThread)
        issuerSDK.expectMsgOnWebhook[Sent]()
      }
    }
  }

  "HolderSDK" - {
    "when try to get un viewed messages" - {
      "should get 'issue-credential' (issue-credential 1.0) message" in {
        val receivedMsg = holderSDK.expectMsgFromConn[IssueCred](oobIssuerHolderConn)
        holderSDK.storeCred(receivedMsg.msg, lastReceivedThread)
      }
    }
  }

  "HolderSDK" - {
    "when try to answer same OOB invitation (from issuer) again" - {
      "by sending 'handshake-reuse' (out-of-band 1.0) message" - {
        "should be successful" in {
          val oobInvite = oobIssueCredInvitation.get
          val handshakeReuse = HandshakeReuse(MsgThread(pthid = Option(oobInvite.`@id`)))
          val msgThread = Option(MsgThread(pthid = Option(oobInvite.`@id`)))
          holderSDK.sendProtoMsgToTheirAgent(oobIssuerHolderConn, handshakeReuse, msgThread)
          holderSDK.expectMsgFromConn[HandshakeReuseAccepted](oobIssuerHolderConn)
          val receivedMsg = issuerSDK.expectMsgOnWebhook[ConnectionReused]()
          receivedMsg.threadOpt.map(_.pthid).isDefined shouldBe true
        }
      }

      "when tried to send 'request-credential' (issue-credential 1.0) message" - {
        "should receive problem report back as asynchronous message" in {
          val oobInvite = oobIssueCredInvitation.get
          val oobOfferCredAttachment = new String(Base64Util.getBase64Decoded(oobInvite.`request~attach`.head.data.base64))
          val attachmentJsonObj = new JSONObject(oobOfferCredAttachment)
          offerCred = JacksonMsgCodec.fromJson[OfferCred](attachmentJsonObj.toString())
          lastReceivedThread = Option(MsgThread(Option(attachmentJsonObj.getJSONObject("~thread").getString("thid"))))
          holderSDK.sendCredRequest(oobIssuerHolderConn, credDefId, offerCred, lastReceivedThread)

          val receivedMsg = holderSDK.expectMsgFromConn[IssueCredProblemReport](oobIssuerHolderConn)
          receivedMsg.msg.description shouldBe ProblemDescription(
            Some("Invalid 'RequestCred' message in current state"), "invalid-message-state")
        }
      }
    }
  }

  "VerifierSDK" - {
    "when created new relationship" - {
      "should be successful" in {
        val receivedMsg = verifierSDK.sendCreateRelationship(oobVerifierHolderConn)
        lastReceivedThread = receivedMsg.threadOpt
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
        verifierSDK.sendMsgForConn(oobVerifierHolderConn, msg)

        val invitation = verifierSDK.expectMsgOnWebhook[ProofReqInvitation]().msg
        val oobValue = invitation.inviteURL.split("\\?oob=").last
        proofReqInvitation = Option(invitation)
        oobProofReqInvitation = Option(JacksonMsgCodec.fromJson[OutOfBandInvitation](new String(Base64Util.getBase64UrlDecoded(oobValue))))
      }
    }
  }

  "HolderSDK" - {
    "as there is no previous connection with the verifier" - {
      "when tried to accept the OOB invitation first time" - {
        "should be successful" in {
          holderSDK.sendCreateNewKey(oobVerifierHolderConn)
          val proofReqInvite = proofReqInvitation.get
          val relInvite = Invitation(proofReqInvite.inviteURL, proofReqInvite.shortInviteURL, proofReqInvite.invitationId)
          holderSDK.sendConnReqForInvitation(oobVerifierHolderConn, relInvite)
          verifierSDK.expectConnectionComplete(oobVerifierHolderConn)
        }
      }

      "when tried to send 'presentation' (present-proof 1.0) message" - {
        "should be successful" in {
          val oobInvite = oobProofReqInvitation.get
          val oobProofReqAttachment = new String(Base64Util.getBase64Decoded(oobInvite.`request~attach`.head.data.base64))
          val attachmentJsonObj = new JSONObject(oobProofReqAttachment)
          reqPresentation = JacksonMsgCodec.fromJson[RequestPresentation](attachmentJsonObj.toString())
          lastReceivedThread = Option(MsgThread(Option(attachmentJsonObj.getJSONObject("~thread").getString("thid"))))
          holderSDK.acceptProofReq(oobVerifierHolderConn, reqPresentation, Map.empty, lastReceivedThread)
        }
      }
    }
  }

  "VerifierSDK" - {
    "should receive 'presentation-result' (present-proof 1.0) message on webhook" in {
      val receivedMsgParam = verifierSDK.expectMsgOnWebhook[PresentationResult]()
      val requestPresentation = receivedMsgParam.msg.requested_presentation
      requestPresentation.revealed_attrs.size shouldBe 2
      requestPresentation.unrevealed_attrs.size shouldBe 0
      requestPresentation.self_attested_attrs.size shouldBe 0
    }
  }

  "HolderSDK" - {
    "when try to answer same OOB invitation (from verifier) again" - {
      "by sending 'handshake-reuse' (out-of-band 1.0) message" - {
        "should be successful" in {
            val oobInvite = oobProofReqInvitation.get
            val handshakeReuse = HandshakeReuse(MsgThread(pthid = Option(oobInvite.`@id`)))
            val msgThread = Option(MsgThread(pthid = Option(oobInvite.`@id`)))
            holderSDK.sendProtoMsgToTheirAgent(oobVerifierHolderConn, handshakeReuse, msgThread)
            holderSDK.expectMsgFromConn[HandshakeReuseAccepted](oobVerifierHolderConn)
            val receivedMsg = verifierSDK.expectMsgOnWebhook[ConnectionReused]()
            receivedMsg.threadOpt.map(_.pthid).isDefined shouldBe true
          }
      }

      "when tried to send 'presentation' (present-proof 1.0) message" - {
        "receive problem report back as asynchronous message" in {
          val oobInvite = oobProofReqInvitation.get
          val oobProofReqAttachment = new String(Base64Util.getBase64Decoded(oobInvite.`request~attach`.head.data.base64))
          val attachmentJsonObj = new JSONObject(oobProofReqAttachment)
          reqPresentation = JacksonMsgCodec.fromJson[RequestPresentation](attachmentJsonObj.toString())
          lastReceivedThread = Option(MsgThread(Option(attachmentJsonObj.getJSONObject("~thread").getString("thid"))))
          holderSDK.acceptProofReq(oobVerifierHolderConn, reqPresentation, Map.empty, lastReceivedThread)
          val receivedMsg = holderSDK.expectMsgFromConn[PresentProofProblemReport](oobVerifierHolderConn)
          receivedMsg.msg.description shouldBe ProblemDescription(
            Some("Invalid 'Presentation' message in current state"), "invalid-message-state")
        }
      }
    }
  }
}