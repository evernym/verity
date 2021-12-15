package com.evernym.integrationtests.e2e.sdk.vcx

import java.util.{Base64, UUID}
import com.evernym.integrationtests.e2e.msg.VcxGetMsg.{vcxPayloadObject, _}
import com.evernym.integrationtests.e2e.sdk.UndefinedInterfaces.UndefinedCommittedAnswer_1_0
import com.evernym.integrationtests.e2e.sdk.vcx.VcxCommittedAnswer.HolderCommittedAnswer
import com.evernym.integrationtests.e2e.sdk.vcx.VcxSdkProvider.Interaction
import com.evernym.sdk.vcx.connection.ConnectionApi
import com.evernym.verity.actor.agent.TypeFormat
import com.evernym.verity.agentmsg.buildAgentMsg
import com.evernym.verity.did.DidStr
import com.evernym.verity.did.didcomm.v1.messages.MsgFamily
import com.evernym.verity.protocol.protocols.committedAnswer.v_1_0.Msg.Answer
import com.evernym.verity.protocol.protocols.committedAnswer.v_1_0.{CommittedAnswerDefinition, CommittedAnswerMsgFamily, CommittedAnswerProtocol, Sig}
import com.evernym.verity.sdk.protocols.questionanswer.v1_0.CommittedAnswerV1_0
import com.evernym.verity.sdk.utils.Context
import com.evernym.verity.util.TimeUtil
import org.json.JSONObject

protected trait VcxCommittedAnswer
  extends VcxHolds {

  def committedAnswer_1_0(forRelationship: DidStr,
                          questionText: String,
                          questionDescription: String,
                          validResponses: Seq[String],
                          requireSig: Boolean): CommittedAnswerV1_0 = throw new NotImplementedError

  def committedAnswer_1_0(forRelationship: DidStr,
                          threadId: String,
                          answerStr: String): CommittedAnswerV1_0 = {
    new UndefinedCommittedAnswer_1_0 {
      override def answer(context: Context): Unit = {
        val i = interaction(threadId).asInstanceOf[HolderCommittedAnswer]
        val connHandle = connectionHandle(i.owningDid)

        val nonce = arrayToSeq(i.question.getJSONArray("valid_responses"))
          .find(_.getString("text") == answerStr)
          .map(_.getString("nonce"))
          .getOrElse(throw new Exception("Answer not found in valid responses"))

        val signable = CommittedAnswerProtocol.buildSignable(nonce)
        val signature = Base64.getEncoder.encodeToString(
          ConnectionApi.connectionSignData(
            connHandle,
            signable.bytes,
            signable.bytes.length
          ).get()
        )
        val sigBlock = Sig(signature, signable.encoded, TimeUtil.nowDateString)
        val answerMsg = Answer(sigBlock)
        val sendOptions = new JSONObject()
            .put("msg_type", "answer")
            .put("msg_title", "answer sent")

        ConnectionApi.connectionSendMessage(
          connHandle,
          buildAgentMsg(
            answerMsg,
            UUID.randomUUID().toString,
            threadId,
            CommittedAnswerDefinition,
            TypeFormat.STANDARD_TYPE_FORMAT
          ).jsonStr,
          sendOptions.toString
        ).get()
      }
    }
  }

  def committedAnswer_1_0(forRelationship: DidStr,
                          threadId: String): CommittedAnswerV1_0 = throw new NotImplementedError

  def interactQuestion(metaData: VcxMsgMetaData, payload: JSONObject): JSONObject = {
    val payloadMsg = vcxPayloadObject(payload)
    payloadMsg.put("msg_ref_id", metaData.msgId)
    val threadId = payloadMsg.getString("@id")
    updateInteraction(
      threadId -> HolderCommittedAnswer(
        metaData.did.get,
        payloadMsg,
        metaData.msgId
      )
    )

    updateMessageStatus(metaData)

    new JSONObject()
      .put("@type", MsgFamily.typeStrFromMsgType(CommittedAnswerMsgFamily, "answer-needed"))
      .put("~thread",
        new JSONObject()
          .put("thid", threadId)
      )
  }
}

object VcxCommittedAnswer {
  case class HolderCommittedAnswer(owningDid: DidStr, question: JSONObject, questionMsgId: String) extends Interaction
}
