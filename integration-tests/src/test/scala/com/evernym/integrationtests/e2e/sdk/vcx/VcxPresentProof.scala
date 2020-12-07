package com.evernym.integrationtests.e2e.sdk.vcx

import java.util.UUID

import com.evernym.verity.protocol.engine.{DID, MsgFamily}
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.PresentProofMsgFamily
import com.evernym.verity.sdk.protocols.presentproof.common.{Predicate, ProposedAttribute, ProposedPredicate}
import org.json.JSONArray
//import com.evernym.verity.protocol.protocols.vcx.presentProof.v_0_6.ProvingMsgFamily
import com.evernym.integrationtests.e2e.msg.VcxGetMsg.vcxPayloadObject
import com.evernym.integrationtests.e2e.sdk.UndefinedInterfaces.UndefinedPresentProof_1_0
import com.evernym.integrationtests.e2e.sdk.vcx.VcxPresentProof.HolderProofInteraction
import com.evernym.integrationtests.e2e.sdk.vcx.VcxSdkProvider.Interaction
import com.evernym.sdk.vcx.proof.DisclosedProofApi
import com.evernym.verity.sdk.protocols.presentproof.common.Attribute
import com.evernym.verity.sdk.protocols.presentproof.v1_0.PresentProofV1_0
import com.evernym.verity.sdk.utils.Context
import org.json.JSONObject

trait VcxPresentProof
  extends VcxHolds {

  def presentProof_1_0(forRelationship: String, proofAttrs: Array[ProposedAttribute], proofPredicates: Array[ProposedPredicate]): PresentProofV1_0 = {
    new UndefinedPresentProof_1_0 {
      override def propose(context: Context): Unit = {
        val connHandle = connectionHandle(forRelationship)

        val proposal = new JSONObject
        proposal.put(
          "attributes",
          proofAttrs.foldLeft(new JSONArray) {(json, attr) => json.put(attr.toJson)}
        )
        proposal.put(
          "predicates",
          proofPredicates.foldLeft(new JSONArray) {(json, pred) => json.put(pred.toJson)}
        )

        val handle = DisclosedProofApi.proofCreateProposal(UUID.randomUUID.toString, proposal.toString, "Proposal").get

        DisclosedProofApi.proofSendProposal(handle, connHandle).get
        DisclosedProofApi.proofRelease(handle)
      }
    }
  }

  def presentProof_1_0(forRelationship: String,
                       name: String,
                       proofAttrs: Array[Attribute],
                       proofPredicate: Array[Predicate],
                       byInvitation: Boolean = false): PresentProofV1_0 = throw new NotImplementedError

  def presentProof_1_0(forRelationship: DID, threadId: String): PresentProofV1_0 = {
    new UndefinedPresentProof_1_0 {
      override def acceptRequest(context: Context): Unit = {
        val i = interaction(threadId).asInstanceOf[HolderProofInteraction]
        val connHandle = connectionHandle(i.owningDid)

        val handle = DisclosedProofApi.proofCreateWithRequest(threadId, i.request.toString()).get

        val creds = new JSONObject(
          DisclosedProofApi.proofRetrieveCredentials(handle).get()
        )
        val attrsObject = creds.getJSONObject("attrs")
        val attrs = attrsObject.keySet()
        val selectedCredentials = new JSONObject
        attrs.forEach { el =>
          val credential = new JSONObject
          credential.put("credential", attrsObject.getJSONArray(el).get(0))
          selectedCredentials.put(el, credential)
        }
        creds.put("attrs", selectedCredentials)
        DisclosedProofApi.proofGenerate(handle, creds.toString(), "{}").get()
        DisclosedProofApi.proofSend(handle, connHandle).get()
        DisclosedProofApi.proofRelease(handle)
      }
    }
  }

  def presentProof_1_0(forRelationship: String, threadId: String, offer: String): PresentProofV1_0 =
    new UndefinedPresentProof_1_0 {
      override def acceptRequest(context: Context): Unit = {
//        val i = interaction(threadId).asInstanceOf[HolderProofInteraction]
        val connHandle = connectionHandle(forRelationship)

        val handle = DisclosedProofApi.proofCreateWithRequest(threadId, offer).get

        val creds = new JSONObject(
          DisclosedProofApi.proofRetrieveCredentials(handle).get()
        )

        val attrsObject = creds.getJSONObject("attrs")
        val attrs = attrsObject.keySet()
        val selectedCredentials = new JSONObject
        attrs.forEach { el =>
          val credential = new JSONObject
          credential.put("credential", attrsObject.getJSONArray(el).get(0))
          selectedCredentials.put(el, credential)
        }
        creds.put("attrs", selectedCredentials)
        DisclosedProofApi.proofGenerate(handle, creds.toString(), "{}").get()
        DisclosedProofApi.proofSend(handle, connHandle).get()
        DisclosedProofApi.proofRelease(handle)
      }
    }

  def interactProofRequest_1_0(metaData: VcxMsgMetaData, payload: JSONObject): JSONObject = {
    val payloadMsg = vcxPayloadObject(payload)
    payloadMsg.put("msg_ref_id", metaData.msgId)
    val threadId = payloadMsg.getString("thread_id")
    val proofHandle = DisclosedProofApi.proofCreateWithRequest(threadId, payloadMsg.toString()).get()
    val credentials = DisclosedProofApi.proofRetrieveCredentials(proofHandle).get()
    DisclosedProofApi.proofRelease(proofHandle)
    updateInteraction(
      threadId -> HolderProofInteraction(
        metaData.did.get,
        payloadMsg
      )
    )
    new JSONObject()
      .put("@type", MsgFamily.typeStrFromMsgType(PresentProofMsgFamily, "ask-accept"))
      .put("~thread",
        new JSONObject()
          .put("thid", threadId)
      )
      .put("proofRequestMsg", payload.toString())
      .put("credentials", credentials)
  }
}

object VcxPresentProof {
  case class HolderProofInteraction(owningDid: DID, request: JSONObject, handle: Option[Int] = None) extends Interaction
}