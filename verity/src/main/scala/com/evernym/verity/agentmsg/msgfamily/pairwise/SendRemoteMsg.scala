package com.evernym.verity.agentmsg.msgfamily.pairwise

import com.evernym.verity.actor.agent.MsgPackVersion.{MPV_INDY_PACK, MPV_MSG_PACK}
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil._
import com.evernym.verity.agentmsg.msgfamily._
import com.evernym.verity.actor.agent.Thread
import com.evernym.verity.protocol.engine.MsgFamily.EVERNYM_QUALIFIER
import com.evernym.verity.agentmsg.msgpacker.{AgentMsgWrapper, MsgFamilyDetail}
import com.evernym.verity.protocol.engine.Constants._
import com.evernym.verity.protocol.engine.{MsgBase, MsgId}
import com.evernym.verity.protocol.protocols.connecting.common.{AgentKeyDlgProof, SenderAgencyDetail, SenderDetail}
import org.json.JSONObject


case class SendRemoteMsgReq_MFV_0_6(`@type`: String,
                                    `@id`: MsgId,
                                    mtype: String,
                                    `@msg`: JSONObject,
                                    sendMsg: Boolean,
                                    `~thread`: Option[Thread] = None,
                                    title: Option[String] = None,
                                    detail: Option[String] = None,
                                    replyToMsgId: Option[String]=None) extends MsgBase {
  override def validate(): Unit = {
    checkRequired("@type", `@type`)
    checkRequired("@id", `@id`)
    checkRequired("@msg", `@msg`)
    checkRequired("mtype", mtype)
    checkOptionalNotEmpty("~thread", `~thread`)
    checkOptionalNotEmpty("title", `title`)
    checkOptionalNotEmpty("detail", detail)
    checkOptionalNotEmpty("replyToMsgId", replyToMsgId)
  }

  def threadOpt: Option[Thread] = `~thread`
}

case class CreateMsgReqMsg_MFV_0_5(`@type`: TypeDetail, mtype: String,
                                   uid: Option[String] = None,
                                   replyToMsgId: Option[String] = None,
                                   thread: Option[Thread]=None,
                                   sendMsg: Boolean = false) extends MsgBase {
  override def validate(): Unit = {
    checkRequired("@type", `@type`)
    checkRequired("mtype", mtype)
    checkOptionalNotEmpty("uid", uid)
    checkOptionalNotEmpty("replyToMsgId", replyToMsgId)
  }
}

case class AnswerMsgReqMsg_MFV_0_5(`@type`: TypeDetail, mtype: String,
                                   uid: Option[String] = None,
                                   replyToMsgId: Option[String] = None,
                                   thread: Option[Thread]=None,
                                   sendMsg: Boolean = false) extends MsgBase {
  override def validate(): Unit = {
    checkRequired("@type", `@type`)
    checkRequired("mtype", mtype)
    checkOptionalNotEmpty("uid", uid)
    checkOptionalNotEmpty("replyToMsgId", replyToMsgId)
  }
}

case class RedirectMsgReqMsg_MFV_0_5(`@type`: TypeDetail, mtype: String,
                                     uid: Option[String] = None,
                                     replyToMsgId: Option[String] = None,
                                     thread: Option[Thread]=None,
                                     sendMsg: Boolean = false) extends MsgBase {
  override def validate(): Unit = {
    checkRequired("@type", `@type`)
    checkRequired("mtype", mtype)
    checkOptionalNotEmpty("uid", uid)
    checkOptionalNotEmpty("replyToMsgId", replyToMsgId)
  }
}

case class RedirectedMsgReqMsg_MFV_0_5(`@type`: TypeDetail, mtype: String,
                                       uid: Option[String] = None,
                                       replyToMsgId: Option[String] = None,
                                       thread: Option[Thread]=None,
                                       sendMsg: Boolean = false) extends MsgBase {
  override def validate(): Unit = {
    checkRequired("@type", `@type`)
    checkRequired("mtype", mtype)
    checkOptionalNotEmpty("uid", uid)
    checkOptionalNotEmpty("replyToMsgId", replyToMsgId)
  }
}



case class GeneralCreateMsgDetail_MFV_0_5(`@type`: TypeDetail,
                                          `@msg`: Array[Byte],
                                          title: Option[String] = None,
                                          detail: Option[String] = None,
                                          senderName: Option[String] = None,
                                          senderLogoUrl: Option[String] = None) extends MsgBase {
  override def validate(): Unit = {
    checkRequired("@type", `@type`)
    checkRequired("@msg", `@msg`)
    checkOptionalNotEmpty("title", title)
    checkOptionalNotEmpty("detail", detail)
    checkOptionalNotEmpty("senderName", senderName)
    checkOptionalNotEmpty("senderLogoUrl", senderLogoUrl)
  }
}


case class InviteCreateMsgDetail_MFV_0_5(`@type`: TypeDetail, keyDlgProof: AgentKeyDlgProof,
                                         phoneNo: Option[String] = None,
                                         targetName: Option[String] = None,
                                         includePublicDID: Option[Boolean] = Option(false)) extends MsgBase {
  override def validate(): Unit = {
    checkRequired("@type", `@type`)
    checkRequired("keyDlgProof", keyDlgProof)
    checkOptionalNotEmpty("phoneNo", phoneNo)
    checkOptionalNotEmpty("targetName", targetName)
  }
}

case class AnswerInviteMsgDetail_MFV_0_5(`@type`: TypeDetail, senderDetail: SenderDetail,
                                         senderAgencyDetail: SenderAgencyDetail,
                                         answerStatusCode: String, keyDlgProof: Option[AgentKeyDlgProof]) extends MsgBase {
  override def validate(): Unit = {
    checkRequired("@type", `@type`)
    checkRequired("senderDetail", senderDetail)
    checkRequired("senderAgencyDetail", senderAgencyDetail)
    checkRequired("answerStatusCode", answerStatusCode)
    checkOptionalNotEmpty("keyDlgProof", keyDlgProof)
  }
}

case class RedirectConnReqMsgDetail_MFV_0_5(`@type`: TypeDetail,
                                            senderDetail: SenderDetail,
                                            senderAgencyDetail: SenderAgencyDetail,
                                            redirectDetail: JSONObject,
                                            keyDlgProof: Option[AgentKeyDlgProof]) extends MsgBase {
  override def validate(): Unit = {
    checkRequired("@type", `@type`)
    checkRequired("senderDetail", senderDetail)
    checkRequired("senderAgencyDetail", senderAgencyDetail)
    checkRequired("redirectDetail", redirectDetail)
    checkOptionalNotEmpty("keyDlgProof", keyDlgProof)
  }
}

case class SendRemoteMsg(msgFamilyDetail: MsgFamilyDetail,
                         id: MsgId,
                         mtype: String,
                         `@msg`: Array[Byte],
                         sendMsg: Boolean,
                         threadOpt: Option[Thread]=None,
                         replyToMsgId: Option[MsgId]=None,
                         title: Option[String] = None,
                         detail: Option[String] = None,
                         senderName: Option[String]=None,
                         senderLogoUrl: Option[String]=None)


case class RemoteMsgSent_MFV_0_6(`@type`: String, `@id`: MsgId, sent: Boolean) extends MsgBase

case class MsgCreatedRespMsg_MFV_0_5(`@type`: TypeDetail, uid: MsgId) extends MsgBase


object SendRemoteMsgHelper {

  def buildRemoteMsgSentResp_MFV_0_6(`@id`: String, sent: Boolean): RemoteMsgSent_MFV_0_6 = {
    RemoteMsgSent_MFV_0_6(MSG_TYPE_DETAIL_REMOTE_MSG_SENT_RESP, `@id`, sent)
  }

  private def buildReqMsgFrom_MFV_0_6(implicit amw: AgentMsgWrapper): SendRemoteMsg = {
    val msg = amw.headAgentMsg.convertTo[SendRemoteMsgReq_MFV_0_6]
    SendRemoteMsg(amw.headAgentMsgDetail, msg.`@id`, msg.mtype, msg.`@msg`.toString().getBytes,
      msg.sendMsg, msg.threadOpt, msg.replyToMsgId, msg.title, msg.detail)
  }

  def buildReqMsg(implicit amw: AgentMsgWrapper): SendRemoteMsg = {
    (amw.msgPackVersion, amw.headAgentMsgDetail) match {
      case (MPV_INDY_PACK, MsgFamilyDetail(EVERNYM_QUALIFIER, MSG_FAMILY_PAIRWISE, MFV_0_6, MSG_TYPE_SEND_REMOTE_MSG, _, _)) =>
        buildReqMsgFrom_MFV_0_6
      case x => throw new RuntimeException("send remote msg req builder failed: " + x)
    }
  }

  def buildRespMsg(id: String)(implicit agentMsgContext: AgentMsgContext): List[Any] = {
    (agentMsgContext.msgPackVersion, agentMsgContext.familyVersion) match {
      case (MPV_MSG_PACK, MFV_0_5)  => List(buildMsgCreatedResp_MFV_0_5(id))
      case (MPV_INDY_PACK, MFV_0_6) => List(buildRemoteMsgSentResp_MFV_0_6(id, sent=true))
      case x => throw new RuntimeException("send remote msg resp builder failed: " + x)
    }
  }
}
