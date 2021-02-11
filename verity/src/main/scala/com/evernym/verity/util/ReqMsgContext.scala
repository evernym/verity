package com.evernym.verity.util

import java.time.LocalDateTime
import java.util.UUID

import com.evernym.verity.constants.Constants.{CLIENT_IP_ADDRESS, MSG_PACK_VERSION}
import com.evernym.verity.Exceptions.BadRequestErrorException
import com.evernym.verity.Status.DATA_NOT_FOUND
import com.evernym.verity.actor.agent.MsgPackFormat
import com.evernym.verity.agentmsg.msgfamily.AgentMsgContext
import com.evernym.verity.agentmsg.msgpacker.MsgFamilyDetail
import com.evernym.verity.protocol.engine.VerKey


case object ReqMsgContext {

  val REQ_UID = "ruid"   // request unique id
  val ORIGINAL_MSG_SENDER_VER_KEY = "omsvk"           //original (edge) message sender ver key
  val LATEST_DECRYPTED_MSG_SENDER_VER_KEY = "ldmsvk"  //last decrypted message sender ver key
  val MSG_TYPE_DETAIL = "msg-type-detail"
}

/**
 * there are certain information about requests (like client ip address etc)
 * need to be passed along to the generic message handler code (like in AgentCommon)
 * or to the specific message handler code (in each agent actors)
 * so that they can do some checks/validations etc
 * @param id unique request id (UUID string)
 * @param initData initial data
 */
case class ReqMsgContext(id: String = UUID.randomUUID().toString, initData: Map[String, Any] = Map.empty) {

  val startTime: LocalDateTime = LocalDateTime.now()

  import ReqMsgContext._

  /**
   * map of key value pair
   */
  var data: Map[String, Any] = initData

  def append(newData: Map[String, Any]): Unit = {
    data = data ++ newData
    data.get(LATEST_DECRYPTED_MSG_SENDER_VER_KEY).foreach { ldmv =>
      if (! data.contains(ORIGINAL_MSG_SENDER_VER_KEY)) {
        data = data ++ Map(ORIGINAL_MSG_SENDER_VER_KEY -> ldmv)
      }
    }
  }

  private def getByKeyOpt[T](key: String): Option[T] = data.get(key).map(_.asInstanceOf[T])
  private def getByKeyReq[T](key: String): T = getByKeyOpt(key).getOrElse(
    throw new BadRequestErrorException(DATA_NOT_FOUND.statusCode, Option(s"$key not found in request msg info")))

  def clientIpAddressReq: String = getByKeyReq(CLIENT_IP_ADDRESS)
  def clientIpAddress: Option[String] = getByKeyOpt(CLIENT_IP_ADDRESS)
  def originalMsgSenderVerKeyOpt: Option[VerKey] = getByKeyOpt(ORIGINAL_MSG_SENDER_VER_KEY)
  def originalMsgSenderVerKeyReq: VerKey = getByKeyReq(ORIGINAL_MSG_SENDER_VER_KEY)
  def latestDecryptedMsgSenderVerKey: Option[VerKey] = getByKeyOpt(LATEST_DECRYPTED_MSG_SENDER_VER_KEY)
  def latestDecryptedMsgSenderVerKeyReq: VerKey = getByKeyReq(LATEST_DECRYPTED_MSG_SENDER_VER_KEY)
  def msgFamilyDetailReq: MsgFamilyDetail = getByKeyReq[MsgFamilyDetail](MSG_TYPE_DETAIL)
  def msgFamilyDetail: Option[MsgFamilyDetail] = getByKeyOpt[MsgFamilyDetail](MSG_TYPE_DETAIL)
  def msgPackFormat: MsgPackFormat = MsgPackFormat.fromString(data.getOrElse(MSG_PACK_VERSION, "n/a").toString)

  implicit def agentMsgContext: AgentMsgContext =
    AgentMsgContext(msgPackFormat, msgFamilyDetailReq.familyVersion, originalMsgSenderVerKeyOpt)
}

