package com.evernym.verity.agentmsg.msgfamily.pairwise

import com.evernym.verity.actor.agent.MsgPackFormat.{MPF_INDY_PACK, MPF_MSG_PACK, MPF_PLAIN}
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil._
import com.evernym.verity.agentmsg.msgfamily.{AgentMsgContext, _}
import com.evernym.verity.agentmsg.msgpacker.{AgentMessageWrapper, AgentMsgWrapper, MsgFamilyDetail}
import com.evernym.verity.protocol.engine.Constants._
import com.evernym.verity.did.didcomm.v1.Thread
import com.evernym.verity.did.didcomm.v1.messages.MsgFamily.EVERNYM_QUALIFIER
import com.evernym.verity.protocol.engine.validate.ValidateHelper.{checkOptionalNotEmpty, checkRequired}
import com.evernym.verity.protocol.engine.ThreadId
import com.evernym.verity.protocol.protocols.connecting.common.{AgentKeyDlgProof, InviteDetail, InviteDetailAbbreviated}
import com.evernym.verity.util.MsgIdProvider

// TODO should not be needed here, should remove utils that use it
import com.evernym.verity.agentmsg.DefaultMsgCodec

case class InviteDetailMsg(`@type`: TypeDetail,
                           inviteDetail: InviteDetail,
                           urlToInviteDetail: String,
                           urlToInviteDetailEncoded: String) extends LegacyMsgBase

case class ConnReqMsg_MFV_0_6(`@type`: String,
                              `@id`: String,
                              sendMsg: Boolean,
                              keyDlgProof: Option[AgentKeyDlgProof] = None,
                              phoneNo: Option[String] = None,
                              targetName: Option[String] = None,
                              includePublicDID: Option[Boolean] = None,
                              `~thread`: Option[Thread] = None
                             ) extends LegacyMsgBase {
  override def validate(): Unit = {
    checkRequired("@type", `@type`)
    checkRequired("@id", `@id`)
    checkRequired("keyDlgProof", keyDlgProof)
    checkOptionalNotEmpty("phoneNo", phoneNo)
    checkOptionalNotEmpty("targetName", targetName)
  }
}

case class ConnReqMsg(msgFamilyDetail: MsgFamilyDetail,
                      id: String,
                      sendMsg: Boolean,
                      keyDlgProof: Option[AgentKeyDlgProof] = None,
                      phoneNo: Option[String] = None,
                      targetName: Option[String] = None,
                      includePublicDID: Option[Boolean] = None,
                      threadOpt: Option[Thread] = None)

case class ConnReqRespMsg_MFV_0_6(`@type`: String,
                                  `@id`: String,
                                 `~thread`: Thread,
                                  inviteDetail: InviteDetail,
                                  truncatedInviteDetail: Option[InviteDetailAbbreviated],
                                  urlToInviteDetail: String,
                                  urlToInviteDetailEncoded: String,
                                  sourceId: Option[String]=None) extends LegacyMsgBase

object ConnReqMsgHelper {

  def buildConnReqAgentMsgWrapper_MFV_0_6(kdp: AgentKeyDlgProof, phoneNo: Option[String],
                                          includePublicDID: Option[Boolean] = None,
                                          amw: AgentMsgWrapper): AgentMsgWrapper = {
    val crm = ConnReqMsg_MFV_0_6(
      MSG_TYPE_DETAIL_CONN_REQ,
      MsgIdProvider.getNewMsgId,
      sendMsg = true,
      Option(kdp),
      phoneNo = phoneNo,
      includePublicDID = includePublicDID
    )
    AgentMessageWrapper(DefaultMsgCodec.toJson(crm), amw.msgPackFormat)
  }

  private def buildReqMsgFrom_MFV_0_6(implicit amw: AgentMsgWrapper): ConnReqMsg = {
    val msg = amw.headAgentMsg.convertTo[ConnReqMsg_MFV_0_6]
    ConnReqMsg(amw.headAgentMsgDetail, msg.`@id`,
      msg.sendMsg, msg.keyDlgProof, msg.phoneNo, msg.targetName, msg.includePublicDID, msg.`~thread`)
  }

  def buildReqMsg(implicit amw: AgentMsgWrapper): ConnReqMsg = {
    (amw.msgPackFormat, amw.headAgentMsgDetail) match {
      case (MPF_INDY_PACK, MsgFamilyDetail(EVERNYM_QUALIFIER, MSG_FAMILY_CONNECTING, MFV_0_6, MSG_TYPE_CONN_REQ, _, _)) |
       (MPF_PLAIN, MsgFamilyDetail(EVERNYM_QUALIFIER, MSG_FAMILY_CONNECTING, MFV_0_6, MSG_TYPE_CONN_REQ, _, _))
              => buildReqMsgFrom_MFV_0_6
      case x  => throw new RuntimeException("conn req builder failed: " + x)
    }
  }

  private def buildConnReqResp_MFV_0_6(`@id`: String,
                                       threadId: ThreadId,
                                       inviteDetail: InviteDetail,
                                       truncatedInviteDetail: Option[InviteDetailAbbreviated],
                                       urlToInviteDetail: String,
                                       urlToInviteDetailEncoded: String,
                                       sourceId: Option[String]=None): ConnReqRespMsg_MFV_0_6 = {
    val msgThread = Thread(thid=Option(threadId))
    ConnReqRespMsg_MFV_0_6(MSG_TYPE_DETAIL_CONN_REQ_RESP, `@id`, msgThread, inviteDetail, truncatedInviteDetail,
      urlToInviteDetail, urlToInviteDetailEncoded, sourceId)
  }


  private def buildInviteDetailMsgResp_MFV_0_5(id: InviteDetail, urlToInviteDetail: String,
                                               urlToInviteDetailEncoded: String): InviteDetailMsg = {
    InviteDetailMsg(buildMsgDetailTypeDetail(MTV_1_0), id, urlToInviteDetail, urlToInviteDetailEncoded)
  }

  def buildRespMsg(`@id`: String,
                   threadId: ThreadId,
                   inviteDetail: InviteDetail,
                   urlToInviteDetail: String,
                   urlToInviteDetailEncoded: String,
                   sourceId: Option[String]=None)
                  (implicit agentMsgContext: AgentMsgContext): List[Any] = {
    (agentMsgContext.msgPackFormat, agentMsgContext.familyVersion) match {
      case (MPF_MSG_PACK, MFV_0_5) => List(
        buildMsgCreatedResp_MFV_0_5(`@id`),
        buildInviteDetailMsgResp_MFV_0_5(inviteDetail, urlToInviteDetail, urlToInviteDetailEncoded))

      case (MPF_INDY_PACK, MFV_0_6) =>
        List(buildConnReqResp_MFV_0_6(`@id`, threadId, inviteDetail, None,
          urlToInviteDetail, urlToInviteDetailEncoded, sourceId))

      case (MPF_PLAIN, MFV_0_6) =>
        List(buildConnReqResp_MFV_0_6(`@id`, threadId, inviteDetail, Option(inviteDetail.toAbbreviated),
          urlToInviteDetail, urlToInviteDetailEncoded, sourceId))

      case x => throw new RuntimeException("conn req response builder failed: " + x)
    }
  }
}
