package com.evernym.verity.agentmsg.msgfamily.pairwise

import com.evernym.verity.util2.Status._
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil._
import com.evernym.verity.agentmsg.msgfamily._
import com.evernym.verity.agentmsg.msgpacker.{AgentMsgWrapper, MsgFamilyDetail}
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.protocol.engine.Constants._
import com.evernym.verity.protocol.engine.{MsgBase, ThreadId}
import com.evernym.verity.did.didcomm.v1.Thread
import com.evernym.verity.protocol.engine.validate.ValidateHelper.{checkOptionalNotEmpty, checkRequired}
import com.evernym.verity.protocol.protocols.connecting.common.{AgentKeyDlgProof, SenderAgencyDetail, SenderDetail}
import org.json.JSONObject


case class ConnReqAcceptedMsg_MFV_0_6(`@type`: String,
                                      `@id`: String,
                                      `~thread`: Thread,
                                      sendMsg: Boolean,
                                      senderDetail: SenderDetail,
                                      senderAgencyDetail: SenderAgencyDetail,
                                      replyToMsgId: String,
                                      keyDlgProof: Option[AgentKeyDlgProof]=None,
                                      sourceId: Option[String]=None) extends LegacyMsgBase {
  override def validate(): Unit = {
    checkRequired("@type", `@type`)
    checkRequired("@id", `@id`)
    checkRequired("senderDetail", senderDetail)
    checkRequired("senderAgencyDetail", senderAgencyDetail)
    checkRequired("replyToMsgId", replyToMsgId)
    checkOptionalNotEmpty("keyDlgProof", keyDlgProof)
  }
}

case class ConnReqDeclinedMsg_MFV_0_6(`@type`: String,
                                      `@id`: String,
                                      `~thread`: Thread,
                                      sendMsg: Boolean,
                                      senderDetail: SenderDetail,
                                      senderAgencyDetail: SenderAgencyDetail,
                                      replyToMsgId: String,
                                      keyDlgProof: Option[AgentKeyDlgProof]=None,
                                      sourceId: Option[String]=None) extends LegacyMsgBase {
  override def validate(): Unit = {
    checkRequired("@type", `@type`)
    checkRequired("@id", `@id`)
    checkRequired("senderDetail", senderDetail)
    checkRequired("senderAgencyDetail", senderAgencyDetail)
    checkRequired("replyToMsgId", replyToMsgId)
    checkOptionalNotEmpty("keyDlgProof", keyDlgProof)
  }
}

case class AcceptConnReqMsg_MFV_0_6(`@type`: String,
                                    `@id`: String,
                                    sendMsg: Boolean,
                                    senderDetail: SenderDetail,
                                    senderAgencyDetail: SenderAgencyDetail,
                                    replyToMsgId: String,
                                    keyDlgProof: Option[AgentKeyDlgProof]=None,
                                    `~thread`: Option[Thread] = None) extends LegacyMsgBase {
  override def validate(): Unit = {
    checkRequired("@type", `@type`)
    checkRequired("@id", `@id`)
    checkRequired("senderDetail", senderDetail)
    checkRequired("senderAgencyDetail", senderAgencyDetail)
    checkRequired("replyToMsgId", replyToMsgId)
    checkOptionalNotEmpty("keyDlgProof", keyDlgProof)
  }
}

case class DeclineConnReqMsg_MFV_0_6(`@type`: String,
                                     `@id`: String,
                                     sendMsg: Boolean,
                                     senderDetail: SenderDetail,
                                     senderAgencyDetail: SenderAgencyDetail,
                                     replyToMsgId: String,
                                     keyDlgProof: AgentKeyDlgProof,
                                     `~thread`: Option[Thread] = None) extends LegacyMsgBase {
  override def validate(): Unit = {
    checkRequired("@type", `@type`)
    checkRequired("@id", `@id`)
    checkRequired("replyToMsgId", replyToMsgId)
    checkRequired("keyDlgProof", keyDlgProof)
  }
}

case class ConnReqAnswerMsg(msgFamilyDetail: MsgFamilyDetail, id: String,
                            sendMsg: Boolean,
                            senderDetail: SenderDetail,
                            senderAgencyDetail: SenderAgencyDetail,
                            answerStatusCode: String,
                            replyToMsgId: String,
                            keyDlgProof: Option[AgentKeyDlgProof],
                            msg: Option[Array[Byte]]=None,
                            threadOpt: Option[Thread] = None) extends MsgBase {
  checkRequired("replyToMsgId", replyToMsgId)
}


case class RedirectConnReqMsg_MFV_0_6(
                                       `@type`: String,
                                       `@id`: String,
                                       sendMsg: Boolean,
                                       redirectDetail: JSONObject,
                                       replyToMsgId: String,
                                       senderDetail: SenderDetail,
                                       senderAgencyDetail: SenderAgencyDetail,
                                       keyDlgProof: AgentKeyDlgProof,
                                       `~thread`: Option[Thread] = None) extends LegacyMsgBase {
  override def validate(): Unit = {
    checkRequired("@type", `@type`)
    checkRequired("@id", `@id`)
    checkRequired("redirectDetail", redirectDetail)
    checkRequired("replyToMsgId", replyToMsgId)
    checkRequired("senderDetail", senderDetail)
    checkRequired("senderAgencyDetail", senderAgencyDetail)
    checkRequired("keyDlgProof", keyDlgProof)
  }
}

case class RedirectConnReqMsg(msgFamilyDetail: MsgFamilyDetail,
                              id: String,
                              sendMsg: Boolean,
                              redirectDetail: JSONObject,
                              replyToMsgId: String,
                              senderDetail: SenderDetail,
                              senderAgencyDetail: SenderAgencyDetail,
                              keyDlgProof: Option[AgentKeyDlgProof],
                              threadOpt: Option[Thread] = None) extends MsgBase {
  checkRequired("replyToMsgId", replyToMsgId)

  def keyDlgProofReq: AgentKeyDlgProof = keyDlgProof.getOrElse(
    throw new RuntimeException("keyDlgProof not provided")
  )
}

case class ConnReqRedirectedMsg_MFV_0_5(
                                         `@type`: TypeDetail,
                                         sendMsg: Boolean,
                                         redirectDetail: JSONObject,
                                         replyToMsgId: String,
                                         senderDetail: SenderDetail,
                                         senderAgencyDetail: SenderAgencyDetail) extends LegacyMsgBase {
  override def validate(): Unit = {
    checkRequired("@type", `@type`)
    checkRequired("redirectDetail", redirectDetail)
    checkRequired("replyToMsgId", replyToMsgId)
    checkRequired("senderDetail", senderDetail)
    checkRequired("senderAgencyDetail", senderAgencyDetail)
  }
}

case class ConnReqRedirectedMsg_MFV_0_6(
                                         `@type`: String,
                                         `@id`: String,
                                         `~thread`: Thread,
                                         sendMsg: Boolean,
                                         redirectDetail: JSONObject,
                                         replyToMsgId: String,
                                         senderDetail: SenderDetail,
                                         senderAgencyDetail: SenderAgencyDetail,
                                         sourceId: Option[String]=None) extends LegacyMsgBase {
  override def validate(): Unit = {
    checkRequired("@type", `@type`)
    checkRequired("@id", `@id`)
    checkRequired("redirectDetail", redirectDetail)
    checkRequired("replyToMsgId", replyToMsgId)
    checkRequired("senderDetail", senderDetail)
    checkRequired("senderAgencyDetail", senderAgencyDetail)
  }
}

case class AcceptConnReqRespMsg_MFV_0_6(`@type`: String, `@id`: String, `~thread`: Thread, sourceId: Option[String]=None) extends LegacyMsgBase

case class DeclineConnReqRespMsg_MFV_0_6(`@type`: String, `@id`: String, `~thread`: Thread, sourceId: Option[String]=None) extends LegacyMsgBase

case class ConnReqAcceptedRespMsg_MFV_0_6(`@type`: String, `@id`: String, `~thread`: Thread) extends LegacyMsgBase

case class ConnReqDeclinedRespMsg_MFV_0_6(`@type`: String, `@id`: String, `~thread`: Thread) extends LegacyMsgBase

case class ConnReqRedirectResp_MFV_0_6(`@type`: String, `@id`: String, `~thread`: Thread, sourceId: Option[String]=None) extends LegacyMsgBase

object AcceptConnReqMsgHelper {

  private def buildReqMsgFrom_MFV_0_6(implicit amw: AgentMsgWrapper): ConnReqAnswerMsg = {
    val msg = amw.headAgentMsg.convertTo[AcceptConnReqMsg_MFV_0_6]
    ConnReqAnswerMsg(amw.headAgentMsgDetail, msg.`@id`,
      msg.sendMsg, msg.senderDetail, msg.senderAgencyDetail, MSG_STATUS_ACCEPTED.statusCode, msg.replyToMsgId,
      msg.keyDlgProof, None, msg.`~thread`)
  }

  def buildReqMsg(implicit amw: AgentMsgWrapper): ConnReqAnswerMsg = {
    amw.headAgentMsgDetail.familyVersion match {
      case MFV_0_6 => buildReqMsgFrom_MFV_0_6
      case x => throw new RuntimeException("accept conn request builder failed: " + x)
    }
  }

  def buildAcceptConnReqResp_MFV_0_6(`@id`: String, threadId: ThreadId, sourceId: Option[String]=None): AcceptConnReqRespMsg_MFV_0_6 = {
    AcceptConnReqRespMsg_MFV_0_6(MSG_TYPE_DETAIL_ACCEPT_CONN_REQ_RESP, `@id`, Thread(Option(threadId)), sourceId)
  }

  def buildRespMsg(`@id`: String, threadId: ThreadId, sourceId: Option[String]=None)
                  (implicit agentMsgContext: AgentMsgContext): List[Any] = {
    agentMsgContext.familyVersion match {
      case MFV_0_5 => List(buildMsgCreatedResp_MFV_0_5(`@id`))
      case MFV_0_6 => List(buildAcceptConnReqResp_MFV_0_6(`@id`, threadId, sourceId))
      case x => throw new RuntimeException("accept conn req response builder failed: " + x)
    }
  }
}

object DeclineConnReqMsgHelper {

  private def buildReqMsgFrom_MFV_0_6(implicit amw: AgentMsgWrapper): ConnReqAnswerMsg = {
    val msg = amw.headAgentMsg.convertTo[DeclineConnReqMsg_MFV_0_6]
    ConnReqAnswerMsg(amw.headAgentMsgDetail, msg.`@id`,
      msg.sendMsg, msg.senderDetail, msg.senderAgencyDetail, MSG_STATUS_REJECTED.statusCode, msg.replyToMsgId,
      Option(msg.keyDlgProof), None, msg.`~thread`)
  }

  def buildReqMsg(implicit amw: AgentMsgWrapper): ConnReqAnswerMsg = {
    amw.headAgentMsgDetail.familyVersion match {
      case MFV_0_6 => buildReqMsgFrom_MFV_0_6
      case x => throw new RuntimeException("decline conn req request builder failed: " + x)
    }
  }

  def buildDeclineConnReqResp_MFV_0_6(`@id`: String, threadId: ThreadId, sourceId: Option[String]=None): DeclineConnReqRespMsg_MFV_0_6 = {
    DeclineConnReqRespMsg_MFV_0_6(MSG_TYPE_DETAIL_DECLINE_CONN_REQ_RESP, `@id`, Thread(Option(threadId)), sourceId)
  }


  def buildRespMsg(`@id`: String, threadId: ThreadId, sourceId: Option[String]=None)
                  (implicit agentMsgContext: AgentMsgContext): List[Any] = {
    agentMsgContext.familyVersion match {
      case MFV_0_6 => List(buildDeclineConnReqResp_MFV_0_6(`@id`, threadId, sourceId))
      case x => throw new RuntimeException("decline conn req response builder failed: " + x)
    }
  }
}

object ConnReqAcceptedMsgHelper {

  private def buildReqMsgFrom_MFV_0_6(implicit amw: AgentMsgWrapper): ConnReqAnswerMsg = {
    val msg = amw.headAgentMsg.convertTo[ConnReqAcceptedMsg_MFV_0_6]
    ConnReqAnswerMsg(amw.headAgentMsgDetail, msg.`@id`,
      msg.sendMsg, msg.senderDetail, msg.senderAgencyDetail, MSG_STATUS_ACCEPTED.statusCode, msg.replyToMsgId,
      msg.keyDlgProof, None, Option(msg.`~thread`))
  }

  def buildReqMsg(implicit amw: AgentMsgWrapper): ConnReqAnswerMsg = {
    amw.headAgentMsgDetail.familyVersion match {
      case MFV_0_6 => buildReqMsgFrom_MFV_0_6
      case x => throw new RuntimeException("conn req accepted request builder failed: " + x)
    }
  }
}

object ConnReqDeclinedMsgHelper {

  private def buildReqMsgFrom_MFV_0_6(implicit amw: AgentMsgWrapper): ConnReqAnswerMsg = {
    val msg = amw.headAgentMsg.convertTo[ConnReqDeclinedMsg_MFV_0_6]
    ConnReqAnswerMsg(amw.headAgentMsgDetail, msg.`@id`,
      msg.sendMsg, msg.senderDetail, msg.senderAgencyDetail, MSG_STATUS_REJECTED.statusCode, msg.replyToMsgId,
      msg.keyDlgProof, None, Option(msg.`~thread`))
  }

  def buildReqMsg(implicit amw: AgentMsgWrapper): ConnReqAnswerMsg = {
    amw.headAgentMsgDetail.familyVersion match {
      case MFV_0_6 => buildReqMsgFrom_MFV_0_6
      case x => throw new RuntimeException("conn req accepted request builder failed: " + x)
    }
  }
}


object RedirectConnReqMsgHelper {

  private def buildReqMsgFrom_MFV_0_5(implicit amw: AgentMsgWrapper): RedirectConnReqMsg = {
    val createMsg = DefaultMsgCodec.fromJson[CreateMsgReqMsg_MFV_0_5](amw.headAgentMsg.msg)
    val msgDetail = DefaultMsgCodec.fromJson[RedirectConnReqMsgDetail_MFV_0_5](amw.tailAgentMsgs.head.msg)
    RedirectConnReqMsg(amw.headAgentMsgDetail, getNewMsgUniqueId,
      sendMsg = true, msgDetail.redirectDetail, createMsg.replyToMsgId.get, msgDetail.senderDetail,
      msgDetail.senderAgencyDetail, msgDetail.keyDlgProof, None)
  }

  private def buildReqMsgFrom_MFV_0_6(implicit amw: AgentMsgWrapper): RedirectConnReqMsg = {
    val msg = DefaultMsgCodec.fromJson[RedirectConnReqMsg_MFV_0_6](amw.headAgentMsg.msg)
    RedirectConnReqMsg(amw.headAgentMsgDetail, msg.`@id`,
      msg.sendMsg, msg.redirectDetail, msg.replyToMsgId, msg.senderDetail,
      msg.senderAgencyDetail, Option(msg.keyDlgProof), msg.`~thread`)
  }

  def buildConnReqRedirectedResp_MFV_0_5(`@id`: String): MsgCreatedRespMsg_MFV_0_5 = {
    MsgCreatedRespMsg_MFV_0_5(buildMsgCreatedTypeDetail(MTV_1_0), `@id`)
  }

  def buildConnReqRedirectedResp_MFV_0_6(`@id`: String, threadId: ThreadId, sourceId: Option[String]=None): ConnReqRedirectResp_MFV_0_6 = {
    ConnReqRedirectResp_MFV_0_6(MSG_TYPE_DETAIL_CONN_REQ_REDIRECTED, `@id`, Thread(Option(threadId)), sourceId)
  }

  def buildRespMsg(`@id`: String, threadId: ThreadId, sourceId: Option[String]=None)
                  (implicit agentMsgContext: AgentMsgContext): List[Any] = {
    agentMsgContext.familyVersion match {
      case MFV_0_5 => List(buildConnReqRedirectedResp_MFV_0_5(`@id`))
      case MFV_0_6 => List(buildConnReqRedirectedResp_MFV_0_6(`@id`, threadId, sourceId))
      case x => throw new RuntimeException("redirect conn req response builder failed: " + x)
    }
  }

  def buildReqMsg(implicit amw: AgentMsgWrapper): RedirectConnReqMsg = {
    amw.headAgentMsgDetail.familyVersion match {
      case MFV_0_5 => buildReqMsgFrom_MFV_0_5
      case MFV_0_6 => buildReqMsgFrom_MFV_0_6
      case x => throw new RuntimeException("accept conn request builder failed: " + x)
    }
  }
}

object ConnReqRedirectedMsgHelper {

  private def buildReqMsgFrom_MFV_0_5(implicit amw: AgentMsgWrapper): RedirectConnReqMsg = {
    val createMsg = DefaultMsgCodec.fromJson[CreateMsgReqMsg_MFV_0_5](amw.headAgentMsg.msg)
    val msgDetail = DefaultMsgCodec.fromJson[RedirectConnReqMsgDetail_MFV_0_5](amw.tailAgentMsgs.head.msg)
    RedirectConnReqMsg(amw.headAgentMsgDetail, getNewMsgUniqueId,
      sendMsg = true, msgDetail.redirectDetail, createMsg.replyToMsgId.get, msgDetail.senderDetail,
      msgDetail.senderAgencyDetail, msgDetail.keyDlgProof, None)
  }

  private def buildReqMsgFrom_MFV_0_6(implicit amw: AgentMsgWrapper): RedirectConnReqMsg = {
    val msg = DefaultMsgCodec.fromJson[ConnReqRedirectedMsg_MFV_0_6](amw.headAgentMsg.msg)
    RedirectConnReqMsg(amw.headAgentMsgDetail, msg.`@id`,
      msg.sendMsg, msg.redirectDetail, msg.replyToMsgId, msg.senderDetail, msg.senderAgencyDetail,
      None, Option(msg.`~thread`))
  }

  def buildReqMsg(implicit amw: AgentMsgWrapper): RedirectConnReqMsg = {
    amw.headAgentMsgDetail.familyVersion match {
      case MFV_0_5 => buildReqMsgFrom_MFV_0_5
      case MFV_0_6 => buildReqMsgFrom_MFV_0_6
      case x => throw new RuntimeException("conn req accepted request builder failed: " + x)
    }
  }
}
