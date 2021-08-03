package com.evernym.verity.util

import com.evernym.verity.actor.agent.MsgPackFormat
import com.evernym.verity.actor.agent.MsgPackFormat.MPF_MSG_PACK
import com.evernym.verity.actor.resourceusagethrottling.IpAddress
import com.evernym.verity.agentmsg.msgfamily.AgentMsgContext
import com.evernym.verity.agentmsg.msgpacker.{AgentMsgWrapper, MsgFamilyDetail}
import com.evernym.verity.did.VerKeyStr

import java.time.LocalDateTime
import java.util.UUID

case object ReqMsgContext {
  def empty: ReqMsgContext = ReqMsgContext()
}

/**
 * there are certain information about each request (like client ip address etc)
 * need to be passed along to the message handler code (like in AgentMsgProcessor)
 * or to the specific message handler code (in each agent actors)
 * so that they can do some checks/validations (like resource usage utilization etc)
 *
 * @param id unique request id (UUID string)
 */
case class ReqMsgContext(id: String = UUID.randomUUID().toString,
                         clientReqId: Option[String] = None,
                         clientIpAddress: Option[IpAddress] = None,
                         msgFamilyDetail: Option[MsgFamilyDetail] = None,
                         msgPackFormat: Option[MsgPackFormat] = None,
                         originalSenderVerKey: Option[VerKeyStr] = None,
                         latestMsgSenderVerKey: Option[VerKeyStr] = None) {

  val startTime: LocalDateTime = LocalDateTime.now()


  def clientIpAddressReq: String = clientIpAddress.getOrElse(
    throw new RuntimeException("client ip address not set in request message context")
  )

  def msgPackFormatReq: MsgPackFormat = msgPackFormat.getOrElse(
    throw new RuntimeException("msg pack format not set in request message context")
  )

  def latestMsgSenderVerKeyReq: VerKeyStr = latestMsgSenderVerKey.getOrElse(
    throw new RuntimeException("latest msg sender ver key not set in request message context")
  )

  def originalMsgSenderVerKeyReq: VerKeyStr = originalSenderVerKey.getOrElse(
    throw new RuntimeException("original msg sender ver key not set in request message context")
  )

  def withMsgPackFormat(mpf: MsgPackFormat): ReqMsgContext = {
    copy(msgPackFormat = Option(mpf))
  }

  def withOrigMsgSenderVerKey(verKey: Option[VerKeyStr]): ReqMsgContext = {
    if (originalSenderVerKey.isDefined) this
    else copy(originalSenderVerKey = verKey)
  }

  def withClientIpAddress(ipa: String): ReqMsgContext =
    copy(clientIpAddress = Option(ipa))

  def withClientReqId(cri: Option[String]): ReqMsgContext =
    copy(clientReqId = cri)

  def withAgentMsgWrapper(amw: AgentMsgWrapper): ReqMsgContext = {
    withOrigMsgSenderVerKey(amw.senderVerKey)
      .copy(latestMsgSenderVerKey = amw.senderVerKey)
      .copy(msgPackFormat = Option(amw.msgPackFormat))
      .copy(msgFamilyDetail = Option(amw.headAgentMsgDetail))
  }

  def wrapInBundledMsg: Boolean = msgPackFormatReq match {
    case MPF_MSG_PACK => true
    case _            => false
  }

  private def msgFamilyDetailReq: MsgFamilyDetail = msgFamilyDetail.getOrElse(
    throw new RuntimeException("msg family detail not set in request message context")
  )

  def agentMsgContext: AgentMsgContext =
    AgentMsgContext(msgPackFormatReq, msgFamilyDetailReq.familyVersion, originalSenderVerKey)
}
