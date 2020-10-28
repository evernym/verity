package com.evernym.verity.agentmsg.msgpacker

import com.evernym.verity.constants.Constants._
import com.evernym.verity.actor.agent.SpanUtil.runWithInternalSpan
import com.evernym.verity.actor.agent.msghandler.outgoing.JsonMsg
import com.evernym.verity.actor.agent.MsgPackVersion
import com.evernym.verity.actor.agent.MsgPackVersion.{MPV_INDY_PACK, MPV_MSG_PACK}
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil._
import com.evernym.verity.agentmsg.msgfamily._
import com.evernym.verity.agentmsg.msgfamily.routing.{FwdReqMsg_MFV_0_5, FwdReqMsg_MFV_1_0_1}
import com.evernym.verity.protocol.engine.Constants._
import com.evernym.verity.protocol.engine.MsgFamily.{COMMUNITY_QUALIFIER, EVERNYM_QUALIFIER, typeStrFromMsgType}
import com.evernym.verity.protocol.engine.{DID, MsgFamilyQualifier, MsgName, VerKey}
import com.evernym.verity.util.MessagePackUtil
import com.evernym.verity.vault.{EncryptParam, KeyInfo, SealParam, WalletAccessParam}
import org.json.JSONObject


object AgentMsgPackagingUtil {

  /**
   * creates agent message json string from given native messages (case classes) based on given parameters
   * @param msgs
   * @param wrapInBundledMsgs
   * @return
   */
  def buildAgentMsgJson(msgs: List[Any], wrapInBundledMsgs: Boolean): String = {
    val jsonMsgs = convertToJsonMsgs(msgs)
    if (wrapInBundledMsgs) buildBundledMsg(jsonMsgs)
    else jsonMsgs match {
      case head::Nil => head
      case msgs      => throw new RuntimeException(s"unsupported use case, found ${msgs.size} messages for non bundled message")
    }
  }

  /**
   * build packed message for an agent (non considering any prior routing if any)
   * @param msgPackVersion
   * @param packMsgParam
   * @param agentMsgTransformer
   * @param wap
   * @return
   */
  def buildAgentMsg(msgPackVersion: MsgPackVersion, packMsgParam: PackMsgParam)
                   (implicit agentMsgTransformer: AgentMsgTransformer, wap: WalletAccessParam): PackedMsg = {
    runWithInternalSpan("buildAgentMsg", "AgentMsgPackagingUtil") {
      val agentMsgJson = buildAgentMsgJson(packMsgParam.msgs, packMsgParam.wrapInBundledMsgs)
      agentMsgTransformer.pack(msgPackVersion, agentMsgJson, packMsgParam.encryptParam)
    }
  }

  /**
   * packs given unpacked agent message and adds required forward messages on top of it (as per given routing details)
   * @param msgPackVersion
   * @param packMsgParam
   * @param fwdRoutes
   * @param fwdMsgTypeVersion
   * @param agentMsgTransformer
   * @param wap
   * @return
   */
  def buildRoutedAgentMsgFromPackMsgParam(msgPackVersion: MsgPackVersion, packMsgParam: PackMsgParam,
                                          fwdRoutes: List[FwdRouteMsg], fwdMsgTypeVersion: String = MTV_1_0)
                                         (implicit agentMsgTransformer: AgentMsgTransformer, wap: WalletAccessParam): PackedMsg = {

    val packedAgentMsg = buildAgentMsg(msgPackVersion, packMsgParam)
    buildRoutedAgentMsg(msgPackVersion, packedAgentMsg, fwdRoutes, fwdMsgTypeVersion)
  }

  /**
   * packs given packed msg and adds required forward messages on top of it (as per given routing details)
   * @param msgPackVersion
   * @param packedMsg
   * @param fwdRoutes
   * @param fwdMsgTypeVersion
   * @param agentMsgTransformer
   * @param wap
   * @return
   */
  def buildRoutedAgentMsg(msgPackVersion: MsgPackVersion, packedMsg: PackedMsg,
                          fwdRoutes: List[FwdRouteMsg], fwdMsgTypeVersion: String = MTV_1_0)
                         (implicit agentMsgTransformer: AgentMsgTransformer, wap: WalletAccessParam): PackedMsg = {
    runWithInternalSpan("buildRoutedAgentMsg", "AgentMsgPackagingUtil") {
      var updatedPackedMsg = packedMsg
      fwdRoutes.foreach { fr =>
        val fwdMsg = buildFwdJsonMsg(
          msgPackVersion,
          fr.to,
          updatedPackedMsg.msg,
          fwdMsgTypeVersion = fwdMsgTypeVersion,
          fwdMsgType = packedMsg.metadata.map(_.msgTypeStr)
        )
        updatedPackedMsg = fr.encryptInfo.fold(
          si => agentMsgTransformer.pack(msgPackVersion, fwdMsg, EncryptParam(Set(si.keyInfo), None)),
          ei => agentMsgTransformer.pack(msgPackVersion, fwdMsg, ei)
        )
      }
      updatedPackedMsg
    }
  }

  /**
   * creates forward message as per given parameters
   * @param mpv message pack version
   * @param toDID DID to which the message should be routed to
   * @param msg packed message
   * @param fwdMsgTypeVersion forward message type version
   * @return
   */
  def buildFwdJsonMsg(mpv: MsgPackVersion,
                      toDID: DID,
                      msg: Array[Byte],
                      msgQualifier: MsgFamilyQualifier=EVERNYM_QUALIFIER,
                      msgName: MsgName=MSG_TYPE_FWD,
                      fwdMsgTypeVersion: String = MTV_1_0,
                      fwdMsgType: Option[String] = None
                     ): String = {
    mpv match {
      case MPV_MSG_PACK =>
        val fwdMsg = DefaultMsgCodec.toJson(
          FwdReqMsg_MFV_0_5(TypeDetail(MSG_TYPE_FWD, fwdMsgTypeVersion), toDID, msg, fwdMsgType)
        )
        buildBundledMsg(List(fwdMsg))
      case MPV_INDY_PACK =>
        val fwdJsValue = new JSONObject(new String(msg))
        val fwd = FwdReqMsg_MFV_1_0_1(
          typeStrFromMsgType(msgQualifier, MSG_FAMILY_ROUTING, fwdMsgTypeVersion, msgName),
          toDID,
          fwdJsValue,
          fwdMsgType
        )
        DefaultMsgCodec.toJson(fwd)
      case x            => throw new RuntimeException("unsupported msg pack version: " + x)
    }
  }

  /** routing by keys should be used before did exchange only
    * assumption here is that the 'to' field of forward message will contain the given routing key
    * and recipient of this message know how to route based on 'to' being a ver key
    *
    * @param msgPackVersion message packaging version (message pack or indy pack)
    * @param msg
    * @param routingKeys
    * @return
    */
  //TODO: come back to this and see if this requires any more refactoring
  def packMsgForRoutingKeys(msgPackVersion: MsgPackVersion,
                            msg: Array[Byte],
                            routingKeys: Seq[VerKey],
                            msgType: String
                           )
                           (implicit agentMsgTransformer: AgentMsgTransformer, wap: WalletAccessParam): PackedMsg = {
    runWithInternalSpan("packMsgForRoutingKeys", "AgentMsgPackagingUtil") {
      routingKeys.size match {
        case 0 => PackedMsg(msg)
        case 1 => throw new RuntimeException("insufficient routing keys: " + routingKeys)
        case _ =>
          val to = routingKeys.head
          val remaining = routingKeys.tail
          val encryptWith = remaining.head
          val fwdJsonMsg = buildFwdJsonMsg(MPV_INDY_PACK, to, msg, COMMUNITY_QUALIFIER, MSG_TYPE_FORWARD, fwdMsgType = Option(msgType))
          val newPackedMsg = agentMsgTransformer.pack(msgPackVersion, fwdJsonMsg, EncryptParam(Set(KeyInfo(Left(encryptWith))), None))
          if (remaining.size >= 2) {
            packMsgForRoutingKeys(msgPackVersion, newPackedMsg.msg, remaining, msgType)
          } else {
            newPackedMsg
          }
      }
    }
  }

  /**
   * wraps a given payload (innerAgentMsg) into another message wrapper (this is to support legacy message structure)
   * @param innerAgentMsg
   * @param wrapperMsgType
   * @param wrapperMsgVersion
   * @param wrapperMsgFormat
   * @return
   */
  def buildPayloadWrapperMsg(innerAgentMsg: String,
                             wrapperMsgType: String=PAYLOAD_WRAPPER_MESSAGE_TYPE,
                             wrapperMsgVersion: String=MTV_1_0,
                             wrapperMsgFormat: String=FORMAT_TYPE_JSON): String = {

    val typeObject = new JSONObject()
    typeObject.put(MSG_NAME, wrapperMsgType)
    typeObject.put(VER, wrapperMsgVersion)
    typeObject.put(FORMAT, wrapperMsgFormat)

    val jsonObject = new JSONObject()
    jsonObject.put(`@TYPE`, typeObject)
    jsonObject.put(`@MSG`, innerAgentMsg)

    jsonObject.toString
  }

  def buildPackMsgParam(encryptInfo: EncryptParam, respMsgs: List[Any], wrapInBundledMsgs: Boolean = true):
    PackMsgParam = PackMsgParam(encryptInfo, respMsgs, wrapInBundledMsgs)

  def buildBundledMsg(msgs: List[String]): String = {
    val msgPackedMsgs = msgs.map { m =>
      MessagePackUtil.convertJsonStringToPackedMsg(m)
    }
    DefaultMsgCodec.toJson(BundledMsg_MFV_0_5(msgPackedMsgs))
  }

  def convertToJsonMsgs(msgs: List[Any]): List[String] = {
    msgs map {
      case jm: JsonMsg => jm.msg
      case native: Any => DefaultMsgCodec.toJson(native)
    }
  }

}

case class FwdRouteMsg(to: String, encryptInfo: Either[SealParam, EncryptParam])

/**
 *
 * @param encryptParam encrypt parameter
 * @param msgs list of messages to be packed
 * @param wrapInBundledMsgs only to support legacy bundled messages
 */
case class PackMsgParam(encryptParam: EncryptParam, msgs: List[Any], wrapInBundledMsgs: Boolean)
