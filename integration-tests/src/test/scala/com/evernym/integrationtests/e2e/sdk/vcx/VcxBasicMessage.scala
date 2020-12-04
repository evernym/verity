package com.evernym.integrationtests.e2e.sdk.vcx

import java.util.{Base64, UUID}

import com.evernym.verity.agentmsg.buildAgentMsg
import com.evernym.verity.agentmsg.msgcodec.StandardTypeFormat
import com.evernym.verity.protocol.engine.{DID, MsgFamily}
import com.evernym.verity.protocol.protocols.basicMessage.v_1_0.Msg.Message
import com.evernym.verity.protocol.protocols.basicMessage.v_1_0.{BasicMessageDefinition, BasicMessageMsgFamily, BasicMessage}
import com.evernym.verity.util.TimeUtil
import com.evernym.integrationtests.e2e.msg.VcxGetMsg.{vcxPayloadObject, _}
import com.evernym.integrationtests.e2e.sdk.UndefinedInterfaces.UndefinedBasicMessage_1_0
import com.evernym.integrationtests.e2e.sdk.vcx.VcxBasicMessage.HolderBasicMessage
import com.evernym.integrationtests.e2e.sdk.vcx.VcxSdkProvider.Interaction
import com.evernym.sdk.vcx.connection.ConnectionApi
import com.evernym.verity.actor.agent.TypeFormat
import com.evernym.verity.sdk.protocols.basicmessage.v1_0.BasicMessageV1_0
import com.evernym.verity.sdk.utils.Context
import org.json.JSONObject

protected trait VcxBasicMessage
  extends VcxHolds {

  def basicMessage_1_0(forRelationship: DID,
                       content: String,
                       sent_time: String,
                       localization: String): BasicMessageV1_0 = throw new NotImplementedError

  def interactMessage(metaData: VcxMsgMetaData, payload: JSONObject): JSONObject = {
    val payloadMsg = vcxPayloadObject(payload)
    payloadMsg.put("msg_ref_id", metaData.msgId)
    val threadId = payloadMsg.getJSONObject("~thread").getString("thid")

    updateInteraction(
      threadId -> HolderBasicMessage(
        metaData.did.get,
        payloadMsg,
        metaData.msgId
      )
    )

    new JSONObject()
      .put("@type", MsgFamily.typeStrFromMsgType(BasicMessageMsgFamily, "received-message"))
      .put("~thread",
        new JSONObject()
          .put("thid", threadId)
      )
      .put("~l10n", payloadMsg.getJSONObject("~l10n"))
      .put("sent_time", payloadMsg.getString("sent_time"))
      .put("content", payloadMsg.getString("content"))
  }
}

object VcxBasicMessage {
  case class HolderBasicMessage(owningDid: DID, content: JSONObject, messageMsgId: String) extends Interaction
}
