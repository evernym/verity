package com.evernym.verity.util

import com.evernym.verity.Exceptions._
import com.evernym.verity.agentmsg.DefaultMsgCodec

case class CreateAgencyKey(seed: Option[String] = None)

/**
 * utility methods to convert from/to a 'message packed' message
 */
object MessagePackUtil {

  def convertPackedMsgToJsonString(payload: Array[Byte]): String = {
    val map = convertPackedMsgToMap(payload)
    DefaultMsgCodec.toJson(map)
  }

  /**
   * json string to 'message packed' message
   * @param msg
   * @return
   */
  def convertJsonStringToPackedMsg(msg: String): Array[Byte] = {
    try {
      val map = DefaultMsgCodec.fromJson[Map[String, Any]](msg)
      convertMapToPackedMsg(map)
    } catch {
      case e: IllegalArgumentException if e.getMessage.startsWith("requirement failed") =>
        throw new MissingReqFieldException(Option(s"required attribute not found (missing/empty/null): '$msg'"))
    }
  }

  /**
   * native msg to 'message packed' message
   * @param msg
   * @tparam T
   * @return
   */
  def convertNativeMsgToPackedMsg[T](msg: T): Array[Byte] = {
    try {
      val json: String = DefaultMsgCodec.toJson(msg)
      convertJsonStringToPackedMsg(json)
    } catch {
      case e: IllegalArgumentException if e.getMessage.startsWith("requirement failed") =>
        throw new MissingReqFieldException(Option(s"required attribute not found (missing/empty/null): '$msg'"))
    }
  }

  /**
   * 'message packed' message to map
   * @param payload
   * @return
   */
  private def convertPackedMsgToMap(payload: Array[Byte]): Map[String, Any] = {
    import org.velvia.MsgPackUtils.unpackMap
    unpackMap(payload).asInstanceOf[Map[String, Any]]
  }

  /**
   * map to 'message packed' message
   * @param map
   * @return
   */
  private def convertMapToPackedMsg(map: Map[String, Any]): Array[Byte] = {
    import com.evernym.verity.util.Util._
    packMsgByMsgPackLib(map)
  }
}
