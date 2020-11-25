package com.evernym.integrationtests.e2e.sdk.vcx

import com.evernym.integrationtests.e2e.msg.JSONObjectUtil
import com.evernym.integrationtests.e2e.msg.VcxGetMsg.{vcxPayloadArray, vcxPayloadObject}
import com.evernym.integrationtests.e2e.sdk.UndefinedInterfaces.UndefinedIssueCredential_1_0
import com.evernym.integrationtests.e2e.sdk.vcx.VcxSdkProvider.Interaction
import com.evernym.sdk.vcx.credential.CredentialApi
import com.evernym.verity.protocol.engine.{DID, MsgFamily}
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.IssueCredMsgFamily
import com.evernym.verity.sdk.protocols.issuecredential.v1_0.IssueCredentialV1_0
import com.evernym.verity.sdk.utils.Context
import org.json.{JSONArray, JSONObject}

trait VcxIssueCredential
  extends VcxHolds
{
  import VcxIssueCredential._

   def interactCredOffer_1_0(metaData: VcxMsgMetaData, payload: JSONObject): JSONObject = {
    val payloadMsg = vcxPayloadArray(payload)
    val firstMsg = payloadMsg.getJSONObject(0)
    require(firstMsg.getString("msg_type") == "credential-offer")

    val threadId = firstMsg.getString("thread_id")

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

    val threadId = JSONObjectUtil.threadId(payloadMsg)

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

  def issueCredential_1_0(forRelationship: String, threadId: String, offer: String): IssueCredentialV1_0 =
    new UndefinedIssueCredential_1_0 {

      override def requestCredential(context: Context): Unit = {
        val connHandle = connectionHandle(forRelationship)
        val handle = CredentialApi.credentialCreateWithOffer(threadId, offer).get()
        CredentialApi.credentialSendRequest(handle, connHandle, 0).get()

        updateInteraction(threadId ->
          HolderIssuanceInteraction(forRelationship, new JSONArray("[]"), Some(handle))
        )
      }
    }

  def issueCredentialComplete_1_0(): Unit = {}
}

protected object VcxIssueCredential {
  case class HolderIssuanceInteraction(owningDid: DID, offer: JSONArray, handle: Option[Int] = None) extends Interaction
}