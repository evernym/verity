package com.evernym.verity.agentmsg.msgfamily.pairwise

import com.evernym.verity.Status.{MSG_STATUS_ACCEPTED, MSG_STATUS_REJECTED}
import com.evernym.verity.actor.agent.MsgPackVersion
import com.evernym.verity.actor.agent.MsgPackVersion.{MPV_INDY_PACK, MPV_MSG_PACK}
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil._
import com.evernym.verity.agentmsg.msgfamily._
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.agentmsg.msgpacker.AgentMsgWrapper
import com.evernym.verity.protocol.engine.Constants.MTV_1_0
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.protocols.connecting.common.{SenderAgencyDetail, SenderDetail}
import com.evernym.verity.util.MessagePackUtil.convertNativeMsgToPackedMsg
import org.json.JSONObject

object ConnectingMsgHelper {

  def buildCreateMsgConnReq(amw: AgentMsgWrapper): ConnReqMsg = {
    val createMsgReq = amw.headAgentMsg.convertTo[CreateMsgReqMsg_MFV_0_5]

    val msgDetail = amw.tailAgentMsgs.head.convertTo[InviteCreateMsgDetail_MFV_0_5]
    ConnReqMsg(amw.headAgentMsgDetail, createMsgReq.uid.getOrElse(getNewMsgUniqueId),
      createMsgReq.sendMsg,
      Option(msgDetail.keyDlgProof),
      msgDetail.phoneNo,
      msgDetail.targetName, msgDetail.includePublicDID)
  }

  def buildCreateMsgConnReqAnswer(amw: AgentMsgWrapper): ConnReqAnswerMsg = {

    val createMsgReq = amw.headAgentMsg.convertTo[CreateMsgReqMsg_MFV_0_5]

    val msgDetail = amw.tailAgentMsgs.head.convertTo[AnswerInviteMsgDetail_MFV_0_5]
    ConnReqAnswerMsg(amw.headAgentMsgDetail, createMsgReq.uid.getOrElse(getNewMsgUniqueId),
      createMsgReq.sendMsg,
      msgDetail.senderDetail, msgDetail.senderAgencyDetail,
      msgDetail.answerStatusCode,
      createMsgReq.replyToMsgId.orNull,
      msgDetail.keyDlgProof)
  }

  def buildInviteAnswerPayloadMsg(msgPackVersion: MsgPackVersion, connReqAnswerMsg: ConnReqAnswerMsg, sourceId: Option[String]=None): (MsgName, String) = {
    msgPackVersion match {
      case MPV_MSG_PACK =>
        val internalPayloadMsg = convertNativeMsgToPackedMsg(InviteAnswerPayloadMsg(connReqAnswerMsg.senderDetail))
        val msgType = TypeDetail(CREATE_MSG_TYPE_CONN_REQ_ANSWER, MTV_1_0, Option (PACKAGING_FORMAT_INDY_MSG_PACK))
        (DefaultMsgCodec.toJson(msgType), DefaultMsgCodec.toJson(PayloadMsg_MFV_0_5(msgType, internalPayloadMsg)))
      case MPV_INDY_PACK =>
        val msgType = connReqAnswerMsg.answerStatusCode match {
          case MSG_STATUS_ACCEPTED.statusCode   => MSG_TYPE_DETAIL_CONN_REQ_ACCEPTED
            //right now, only accepted message is what gets send to inviter
            //we don't send declined msg to inviter
        }
        val internalPayloadMsg = new JSONObject(DefaultMsgCodec.toJson(InviteAnswerPayloadMsg(connReqAnswerMsg.senderDetail)))
        (DefaultMsgCodec.toJson(msgType), DefaultMsgCodec.toJson(PayloadMsg_MFV_0_6(msgType, internalPayloadMsg, sourceId)))
      case x            => throw new RuntimeException("unsupported msg pack version: " + x)
    }
  }

  def buildRedirectPayloadMsg(msgPackVersion: MsgPackVersion, senderDetail: SenderDetail, redirectDetail: String): (MsgName, String) = {
    msgPackVersion match {
      case MPV_MSG_PACK =>
       val internalPayloadMsg = convertNativeMsgToPackedMsg(RedirectPayloadMsg_0_5(senderDetail, new JSONObject(redirectDetail)))
        val msgType = TypeDetail(CREATE_MSG_TYPE_CONN_REQ_REDIRECTED, MTV_1_0, Option (PACKAGING_FORMAT_INDY_MSG_PACK))
        (DefaultMsgCodec.toJson(msgType), DefaultMsgCodec.toJson(PayloadMsg_MFV_0_5(msgType, internalPayloadMsg)))

      case MPV_INDY_PACK =>
        val internalPayloadMsg = new JSONObject(DefaultMsgCodec.toJson(
          RedirectPayloadMsg_0_6(senderDetail, new JSONObject(redirectDetail))))
        (MSG_TYPE_DETAIL_CONN_REQ_REDIRECTED,
          DefaultMsgCodec.toJson(PayloadMsg_MFV_0_6(MSG_TYPE_DETAIL_CONN_REQ_REDIRECTED, internalPayloadMsg))
        )
      case x            => throw new RuntimeException("unsupported msg pack version: " + x)
    }
  }

  def buildConnReqAnswerMsgForRemoteCloudAgent(
                                                version: MsgPackVersion,
                                                uid: MsgId,
                                                answerStatusCode: String,
                                                replyToMsgId: String,
                                                senderDetail: SenderDetail,
                                                senderAgencyDetail: SenderAgencyDetail,
                                                threadId: ThreadId
                                              ): List[Any] = {

    version match {
      case MPV_MSG_PACK =>
        List(
          CreateMsgReqMsg_MFV_0_5(
            TypeDetail(MSG_TYPE_CREATE_MSG, MTV_1_0),
            CREATE_MSG_TYPE_CONN_REQ_ANSWER, uid = Option(uid), replyToMsgId = Option(replyToMsgId)),
          AnswerInviteMsgDetail_MFV_0_5(TypeDetail(MSG_TYPE_MSG_DETAIL, MTV_1_0),
            senderDetail, senderAgencyDetail, answerStatusCode, None))

      case MPV_INDY_PACK =>
        answerStatusCode match {
          case MSG_STATUS_ACCEPTED.statusCode =>
            List(ConnReqAcceptedMsg_MFV_0_6(
              MSG_TYPE_DETAIL_CONN_REQ_ACCEPTED,
              uid,
              `~thread`=MsgThread(Option(threadId)),
              sendMsg = false,
              senderDetail,
              senderAgencyDetail,
              replyToMsgId))
          case MSG_STATUS_REJECTED.statusCode =>
            List(ConnReqDeclinedMsg_MFV_0_6(
              MSG_TYPE_CONN_REQ_DECLINED,
              uid,
              `~thread`=MsgThread(Option(threadId)),
              sendMsg = false,
              senderDetail,
              senderAgencyDetail,
              replyToMsgId))
        }
      case x            => throw new RuntimeException("unsupported msg pack version: " + x)
    }
  }

  def buildRedirectedConnReqMsgForRemoteCloudAgent(
                                                version: MsgPackVersion,
                                                uid: MsgId,
                                                replyToMsgId: String,
                                                redirectDetail: JSONObject,
                                                senderDetail: SenderDetail,
                                                senderAgencyDetail: SenderAgencyDetail,
                                                threadId: ThreadId
                                              ): List[Any] = {

    version match {
      case MPV_MSG_PACK =>
        List(
          CreateMsgReqMsg_MFV_0_5(
            TypeDetail(MSG_TYPE_CREATE_MSG, MTV_1_0),
            CREATE_MSG_TYPE_CONN_REQ_REDIRECTED, uid = Option(uid), replyToMsgId = Option(replyToMsgId)),
          RedirectConnReqMsgDetail_MFV_0_5(TypeDetail(MSG_TYPE_MSG_DETAIL, MTV_1_0),
            senderDetail, senderAgencyDetail, redirectDetail, None))

      case MPV_INDY_PACK =>
        List(
          ConnReqRedirectedMsg_MFV_0_6(
          MSG_TYPE_DETAIL_CONN_REQ_REDIRECTED, uid, `~thread`=MsgThread(Option(threadId)),
            sendMsg = false, redirectDetail, replyToMsgId, senderDetail, senderAgencyDetail))
      case x            => throw new RuntimeException("unsupported msg pack version: " + x)
    }
  }

}

case class InviteAnswerPayloadMsg(senderDetail: SenderDetail) extends MsgBase {
  override def validate(): Unit = {
    checkRequired("senderDetail", senderDetail)
  }
}

case class RedirectPayloadMsg_0_5(senderDetail: SenderDetail, redirectDetail: JSONObject) extends MsgBase {
  override def validate(): Unit = {
    checkRequired("senderDetail", senderDetail)
    checkRequired("redirectDetail", redirectDetail)
  }
}

case class RedirectPayloadMsg_0_6(senderDetail: SenderDetail, redirectDetail: JSONObject) extends MsgBase {
  override def validate(): Unit = {
    checkRequired("senderDetail", senderDetail)
    checkRequired("redirectDetail", redirectDetail)
  }
}

case class PayloadMsg_MFV_0_5(`@type`: TypeDetail, `@msg`: Array[Byte]) extends MsgBase {
  override def validate(): Unit = {
    checkRequired("@type", `@type`)
    checkRequired("@msg", `@msg`)
  }
}

case class PayloadMsg_MFV_0_6(`@type`: String, `@msg`: JSONObject, sourceId: Option[String]=None) extends MsgBase {
  override def validate(): Unit = {
    checkRequired("@type", `@type`)
    checkRequired("@msg", `@msg`)
  }
}

case class AcceptedInviteAnswerMsg_0_6(`@msg`: InviteAnswerPayloadMsg, sourceId: Option[String]=None) extends MsgBase {
  override def validate(): Unit = {
    checkRequired("@msg", `@msg`)
  }
}

case class RedirectedInviteAnswerMsg_0_6(`@msg`: RedirectPayloadMsg_0_6) extends MsgBase {
  override def validate(): Unit = {
    checkRequired("@msg", `@msg`)
  }
}
