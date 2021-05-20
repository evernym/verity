package com.evernym.integrationtests.e2e.sdk.vcx

import com.evernym.verity.protocol.engine.{DID, MsgFamily}
import com.evernym.verity.protocol.protocols.basicMessage.v_1_0.BasicMessageMsgFamily
import com.evernym.integrationtests.e2e.msg.VcxGetMsg.vcxPayloadObject
import com.evernym.integrationtests.e2e.sdk.UndefinedInterfaces.UndefinedBasicMessage_1_0
import com.evernym.integrationtests.e2e.sdk.vcx.VcxBasicMessage.HolderBasicMessage
import com.evernym.integrationtests.e2e.sdk.vcx.VcxSdkProvider.Interaction
import com.evernym.sdk.vcx.connection.ConnectionApi
import com.evernym.verity.sdk.protocols.basicmessage.v1_0.BasicMessageV1_0
import com.evernym.verity.sdk.utils.Context
import org.json.JSONObject

trait VcxBasicMessage
  extends VcxHolds {

  def basicMessage_1_0(forRelationship: DID,
                       threadId: String,
                       content: String,
                       sent_time: String,
                       localization: String): BasicMessageV1_0 =
    new UndefinedBasicMessage_1_0 {
      override def message(context: Context): Unit = {

        val messageJSON = new JSONObject ()
          .put("@type", MsgFamily.typeStrFromMsgType(BasicMessageMsgFamily, "message"))
          .put("@id", threadId)
          .put("~l10n",
            new JSONObject()
              .put("locale", localization)
          )
          .put("sent_time", sent_time)
          .put("content", content)

        val messageOptionsJSON = new JSONObject ()
          .put("msg_type", "basicmessage")
          .put("msg_title", "Test")

        val connHandle = connectionHandle(forRelationship)
        val basicMessage = ConnectionApi.connectionSendMessage(connHandle, messageJSON.toString(), messageOptionsJSON.toString()).get()

        updateInteraction(threadId ->
          HolderBasicMessage(forRelationship, messageJSON, threadId)
        )
      }

  }

  def interactMessage(metaData: VcxMsgMetaData, payload: JSONObject): JSONObject = {
    val payloadMsg = vcxPayloadObject(payload)
    payloadMsg.put("msg_ref_id", metaData.msgId)
    val threadId = payloadMsg.getString("@id")

    updateInteraction(
      threadId -> HolderBasicMessage(
        metaData.did.get,
        payloadMsg,
        metaData.msgId
      )
    )

    updateMessageStatus(metaData)

    new JSONObject()
      .put("@type", MsgFamily.typeStrFromMsgType(BasicMessageMsgFamily, "received-message"))
      .put("~thread",
        new JSONObject()
          .put("thid", threadId)
      )
      .put("relationship", metaData.did.get)
      .put("~l10n", payloadMsg.getJSONObject("~l10n"))
      .put("sent_time", payloadMsg.getString("sent_time"))
      .put("content", payloadMsg.getString("content"))
  }

}

object VcxBasicMessage {
  case class HolderBasicMessage(owningDid: DID, content: JSONObject, messageMsgId: String) extends Interaction
}
