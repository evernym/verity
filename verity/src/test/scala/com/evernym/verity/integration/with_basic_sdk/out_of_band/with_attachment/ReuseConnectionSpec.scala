package com.evernym.verity.integration.with_basic_sdk.out_of_band.with_attachment

import com.evernym.verity.did.didcomm.v1.{Thread => MsgThread}
import com.evernym.verity.agentmsg.msgcodec.jackson.JacksonMsgCodec
import com.evernym.verity.integration.base.sdk_provider.SdkProvider
import com.evernym.verity.integration.base.{CAS, VAS, VerityProviderBaseSpec}
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.Ctl.{Issue, Offer}
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.Msg.{IssueCred, OfferCred}
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.Sig.{AcceptRequest, Invitation, Sent}
import com.evernym.verity.protocol.protocols.outofband.v_1_0.Msg.{HandshakeReuse, HandshakeReuseAccepted, OutOfBandInvitation}
import com.evernym.verity.protocol.protocols.outofband.v_1_0.Signal.ConnectionReused
import com.evernym.verity.protocol.protocols.writeCredentialDefinition.{v_0_6 => writeCredDef0_6}
import com.evernym.verity.protocol.protocols.writeSchema.{v_0_6 => writeSchema0_6}
import com.evernym.verity.util.Base64Util
import org.json.JSONObject


//Holder and Issuer already have a connection.
//Holder receives a new "cred offer attached OOB invitation" from the same Issuer.
// Holder re-uses the existing connection (handshake-reuse) and move forward successfully with OOB attached cred offer

class ReuseConnectionSpec
  extends VerityProviderBaseSpec
    with SdkProvider {

  lazy val issuerVerityEnv = VerityEnvBuilder.default().build(VAS)
  lazy val holderVerityEnv = VerityEnvBuilder.default().build(CAS)

  lazy val issuerSDK = setupIssuerSdk(issuerVerityEnv)
  lazy val holderSDK = setupHolderSdk(holderVerityEnv, defaultSvcParam.ledgerTxnExecutor)

  val issuerHolderConn = "connId1"
  val oobIssuerHolderConn = "connId2"

  var schemaId: SchemaId = _
  var credDefId: CredDefId = _
  var offerCred: OfferCred = _

  var lastReceivedThread: Option[MsgThread] = None
  var oobInvitation: Option[OutOfBandInvitation] = None

  override def beforeAll(): Unit = {
    super.beforeAll()
    provisionEdgeAgent(issuerSDK)
    provisionCloudAgent(holderSDK)

    setupIssuer(issuerSDK)
    schemaId = writeSchema(issuerSDK, writeSchema0_6.Write("name", "1.0", Seq("name", "age")))
    credDefId = writeCredDef(issuerSDK, writeCredDef0_6.Write("name", schemaId, None, None))

    establishConnection(issuerHolderConn, issuerSDK, holderSDK)
  }

  "IssuerSDK" - {
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
        val invitation = issuerSDK.expectMsgOnWebhook[Invitation]().msg
        val oobValue = invitation.inviteURL.split("\\?oob=").last
        oobInvitation = Option(JacksonMsgCodec.fromJson[OutOfBandInvitation](new String(Base64Util.getBase64UrlDecoded(oobValue))))
      }
    }
  }

  "HolderSDK" - {

    "when try to send 'handshake-reuse' (out-of-band 1.0) message" - {
      "should be successful" in {
        val oobInvite = oobInvitation.get
        val handshakeReuse = HandshakeReuse(MsgThread(pthid = Option(oobInvite.`@id`)))
        val msgThread = Option(MsgThread(pthid = Option(oobInvite.`@id`)))
        holderSDK.sendProtoMsgToTheirAgent(issuerHolderConn, handshakeReuse, msgThread)
        holderSDK.expectMsgFromConn[HandshakeReuseAccepted](issuerHolderConn)
        val receivedMsg = issuerSDK.expectMsgOnWebhook[ConnectionReused]()
        receivedMsg.threadOpt.map(_.pthid).isDefined shouldBe true
        java.lang.Thread.sleep(2000)  //time to let "move protocol" finish on verity side
      }
    }

    "when tried to 'request-credential' (issue-credential 1.0) message" - {
      "should be successful" in {
        val oobInvite = oobInvitation.get
        val oobOfferCredAttachment = new String(Base64Util.getBase64Decoded(oobInvite.`request~attach`.head.data.base64))
        val attachmentJsonObj = new JSONObject(oobOfferCredAttachment)
        offerCred = JacksonMsgCodec.fromJson[OfferCred](attachmentJsonObj.toString())
        lastReceivedThread = Option(MsgThread(Option(attachmentJsonObj.getJSONObject("~thread").getString("thid"))))
        holderSDK.sendCredRequest(issuerHolderConn, credDefId, offerCred, lastReceivedThread)
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
        issuerSDK.sendMsgForConn(issuerHolderConn, issueMsg, lastReceivedThread)
        issuerSDK.expectMsgOnWebhook[Sent]()
      }
    }
  }

  "HolderSDK" - {
    "when try to get un viewed messages" - {
      "should get 'issue-credential' (issue-credential 1.0) message" in {
        val receivedMsg = holderSDK.expectMsgFromConn[IssueCred](issuerHolderConn)
        holderSDK.storeCred(receivedMsg.msg, lastReceivedThread)
      }
    }
  }
}