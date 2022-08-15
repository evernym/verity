package com.evernym.verity.integration.with_basic_sdk.out_of_band.without_attachment

import com.evernym.verity.did.didcomm.v1.{Thread => MsgThread}
import com.evernym.verity.integration.base.sdk_provider.{HolderSdk, IssuerSdk, SdkProvider}
import com.evernym.verity.integration.base.{CAS, VAS, VerityProviderBaseSpec}
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.Msg.OfferCred
import com.evernym.verity.protocol.protocols.relationship.v_1_0.Signal.Invitation
import com.evernym.verity.protocol.protocols.writeCredentialDefinition.{v_0_6 => writeCredDef0_6}
import com.evernym.verity.protocol.protocols.writeSchema.{v_0_6 => writeSchema0_6}

import scala.concurrent.Await


//Holder1 connects with an Issuer via an OOB invitation.
//Then Holder2 tries to re-use the 'same OOB invitation' (which is already accepted)
// and the expectation is that it should fail.

class ReuseInvitationSpec
  extends VerityProviderBaseSpec
    with SdkProvider {

  var issuerSDK: IssuerSdk = _
  var holderSDK1: HolderSdk = _
  var holderSDK2: HolderSdk = _

  val oobIssuerHolderConn1 = "connId1"
  val oobIssuerHolderConn2 = "connId2"

  var schemaId: SchemaId = _
  var credDefId: CredDefId = _
  var offerCred: OfferCred = _

  var lastReceivedThread: Option[MsgThread] = None
  var oobInvite: Option[Invitation] = None

  override def beforeAll(): Unit = {
    super.beforeAll()

    val issuerVerityEnvFut = VerityEnvBuilder().buildAsync(VAS)
    val holderVerityEnvFut = VerityEnvBuilder().buildAsync(CAS)

    val issuerSDKFut = setupIssuerSdkAsync(issuerVerityEnvFut, executionContext)
    val holderSDK1Fut = setupHolderSdkAsync(holderVerityEnvFut, defaultSvcParam.ledgerTxnExecutor, defaultSvcParam.vdrTools, executionContext)
    val holderSDK2Fut = setupHolderSdkAsync(holderVerityEnvFut, defaultSvcParam.ledgerTxnExecutor, defaultSvcParam.vdrTools, executionContext)

    issuerSDK = Await.result(issuerSDKFut, SDK_BUILD_TIMEOUT)
    holderSDK1 = Await.result(holderSDK1Fut, SDK_BUILD_TIMEOUT)
    holderSDK2 = Await.result(holderSDK2Fut, SDK_BUILD_TIMEOUT)

    provisionEdgeAgent(issuerSDK)
    provisionCloudAgent(holderSDK1)
    provisionCloudAgent(holderSDK2)

    setupIssuer_v0_6(issuerSDK)
    schemaId = writeSchema_v0_6(issuerSDK, writeSchema0_6.Write("name", "1.0", Seq("name", "age")))
    credDefId = writeCredDef_v0_6(issuerSDK, writeCredDef0_6.Write("name", schemaId, None, None))
  }

  "IssuerSDK" - {

    "when created new relationship" - {
      "should be successful" in {
        val receivedMsg = issuerSDK.sendCreateRelationship(oobIssuerHolderConn1)
        lastReceivedThread = receivedMsg.threadOpt
      }
    }

    "when created new OOB invitation" - {
      "should be successful" in {
        oobInvite = Option(issuerSDK.sendCreateOOBInvitation(oobIssuerHolderConn1, lastReceivedThread))
      }
    }
  }

  "HolderSDK1" - {
    "as there is no previous connection with the issuer" - {
      "when tried to accept the OOB invitation" - {
        "should be successful" in {
          holderSDK1.sendCreateNewKey(oobIssuerHolderConn1)
          val invite = oobInvite.get
          holderSDK1.sendConnReqForInvitation(oobIssuerHolderConn1, invite)
          issuerSDK.expectConnectionComplete(oobIssuerHolderConn1)
        }
      }
    }
  }

  "HolderSDK2" - {
    "as there is no previous connection with the issuer" - {
      "when tried to use already accepted OOB invitation" - {
        "should respond with error" in {
          holderSDK2.sendCreateNewKey(oobIssuerHolderConn1)
          val invite = oobInvite.get
          val ex = intercept[IllegalArgumentException] {
            holderSDK2.sendConnReqForInvitation(oobIssuerHolderConn1, invite)
          }
          ex.getMessage.contains("unauthorized")
        }
      }
    }
  }
}
