package com.evernym.integrationtests.e2e.sdk.vcx

import com.evernym.verity.protocol.engine.{DID, MsgFamily}
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.IssueCredMsgFamily
//import com.evernym.verity.protocol.protocols.vcx.issueCredential.v_0_6.IssuingMsgFamily
import com.evernym.verity.util.HashAlgorithm.SHA256
import com.evernym.verity.util.HashUtil.{safeMultiHash, _}
import com.evernym.integrationtests.e2e.msg.VcxGetMsg.{vcxPayloadArray, vcxPayloadObject}
import com.evernym.integrationtests.e2e.sdk.UndefinedInterfaces.UndefinedIssueCredential_1_0
import com.evernym.integrationtests.e2e.sdk.vcx.VcxSdkProvider.Interaction
import com.evernym.sdk.vcx.credential.CredentialApi
import com.evernym.verity.sdk.protocols.issuecredential.v1_0.IssueCredentialV1_0
import com.evernym.verity.sdk.utils.Context
import org.json.{JSONArray, JSONObject}

protected trait VcxIssueCredential
  extends VcxHolds
{
  import VcxIssueCredential._

   def interactCredOffer_1_0(metaData: VcxMsgMetaData, payload: JSONObject): JSONObject = {
    val payloadMsg = vcxPayloadArray(payload)
    val firstMsg = payloadMsg.getJSONObject(0)
    require(firstMsg.getString("msg_type") == "credential-offer")

    val credId = firstMsg.getString("cred_def_id")

    val threadId = safeMultiHash(SHA256, metaData.senderDid, credId).hex

    updateInteraction(
      threadId -> HolderIssuanceInteraction(
        metaData.did.get,
        payloadMsg
      )
    )
    new JSONObject()
      .put("@type", MsgFamily.typeStrFromMsgType(IssueCredMsgFamily, "ask-accept"))
      .put("~thread",
        new JSONObject()
          .put("thid", threadId)
      )
  }

  def interactCred_1_0(metaData: VcxMsgMetaData, payload: JSONObject): JSONObject = {
    val payloadMsg = vcxPayloadObject(payload)
    val credId = payloadMsg.getString("cred_def_id")

    val threadId = safeMultiHash(SHA256, metaData.senderDid, credId).hex

    val i = interaction(threadId).asInstanceOf[HolderIssuanceInteraction]
    val handle = i.handle.get
    CredentialApi.credentialUpdateState(handle).get()
    assert(CredentialApi.credentialGetState(handle).get() == 4)

    CredentialApi.credentialRelease(handle)

    new JSONObject()
      .put("@type", MsgFamily.typeStrFromMsgType(IssueCredMsgFamily, "cred-received"))
      .put("~thread",
        new JSONObject()
          .put("thid", threadId)
      )
  }

  def issueCredential_1_0(forRelationship: String, threadId: String): IssueCredentialV1_0 =
    new UndefinedIssueCredential_1_0 {

      override def requestCredential(context: Context): Unit = {
        val i = interaction(threadId).asInstanceOf[HolderIssuanceInteraction]
        val connHandle = connectionHandle(i.owningDid)
        val handle = CredentialApi.credentialCreateWithOffer(threadId, i.offer.toString()).get()
        CredentialApi.credentialSendRequest(handle, connHandle, 0).get()

        updateInteraction(threadId -> i.copy(handle=Some(handle)))
      }
  }

  def issueCredentialComplete_1_0(): Unit = {}
}

protected object VcxIssueCredential {
  case class HolderIssuanceInteraction(owningDid: DID, offer: JSONArray, handle: Option[Int] = None) extends Interaction
}