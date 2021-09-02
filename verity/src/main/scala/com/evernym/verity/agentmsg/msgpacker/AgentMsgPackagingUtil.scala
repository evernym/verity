package com.evernym.verity.agentmsg.msgpacker

import com.evernym.verity.constants.Constants._
import com.evernym.verity.actor.agent.msghandler.outgoing.JsonMsg
import com.evernym.verity.actor.agent.MsgPackFormat
import com.evernym.verity.actor.agent.MsgPackFormat.{MPF_INDY_PACK, MPF_MSG_PACK}
import com.evernym.verity.agentmsg.DefaultMsgCodec

import scala.concurrent.ExecutionContext
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil._
import com.evernym.verity.agentmsg.msgfamily._
import com.evernym.verity.agentmsg.msgfamily.routing.{FwdReqMsg_MFV_0_5, FwdReqMsg_MFV_1_0_1}
import com.evernym.verity.protocol.engine.Constants._
import com.evernym.verity.util.MessagePackUtil
import com.evernym.verity.actor.wallet.PackedMsg
import com.evernym.verity.did.didcomm.v1.messages.MsgFamily.{COMMUNITY_QUALIFIER, EVERNYM_QUALIFIER, MsgFamilyQualifier, MsgName, typeStrFromMsgType}
import com.evernym.verity.did.{DidStr, VerKeyStr}
import com.evernym.verity.observability.metrics.{InternalSpan, MetricsWriter}
import com.evernym.verity.vault.{EncryptParam, KeyParam, SealParam, WalletAPIParam}
import org.json.JSONObject

import scala.concurrent.Future


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
   * @param msgPackFormat
   * @param packMsgParam
   * @param agentMsgTransformer
   * @param wap
   * @return
   */
  def buildAgentMsg(msgPackFormat: MsgPackFormat,
                    packMsgParam: PackMsgParam)
                   (implicit agentMsgTransformer: AgentMsgTransformer,
                    wap: WalletAPIParam,
                    mw: MetricsWriter): Future[PackedMsg] = {
    mw.runWithSpan("buildAgentMsg", "AgentMsgPackagingUtil", InternalSpan) {
      val agentMsgJson = buildAgentMsgJson(packMsgParam.msgs, packMsgParam.wrapInBundledMsgs)
      agentMsgTransformer.packAsync(msgPackFormat, agentMsgJson, packMsgParam.encryptParam)
    }
  }

  /**
   * packs given unpacked agent message and adds required forward messages on top of it (as per given routing details)
   * @param msgPackFormat
   * @param packMsgParam
   * @param fwdRoutes
   * @param fwdMsgTypeVersion
   * @param agentMsgTransformer
   * @param wap
   * @return
   */
  def buildRoutedAgentMsgFromPackMsgParam(msgPackFormat: MsgPackFormat,
                                          packMsgParam: PackMsgParam,
                                          fwdRoutes: List[FwdRouteMsg],
                                          fwdMsgTypeVersion: String = MTV_1_0)
                                         (implicit agentMsgTransformer: AgentMsgTransformer,
                                          wap: WalletAPIParam,
                                          mw: MetricsWriter,
                                          ec: ExecutionContext): Future[PackedMsg] = {

    buildAgentMsg(msgPackFormat, packMsgParam).flatMap { packedAgentMsg =>
      buildRoutedAgentMsg(msgPackFormat, packedAgentMsg, fwdRoutes, fwdMsgTypeVersion)
    }
  }

  /**
   * packs given packed msg and adds required forward messages on top of it (as per given routing details)
   * @param msgPackFormat
   * @param packedMsg
   * @param fwdRoutes
   * @param fwdMsgTypeVersion
   * @param agentMsgTransformer
   * @param wap
   * @return
   */
  def buildRoutedAgentMsg(msgPackFormat: MsgPackFormat,
                          packedMsg: PackedMsg,
                          fwdRoutes: List[FwdRouteMsg],
                          fwdMsgTypeVersion: String = MTV_1_0)
                         (implicit agentMsgTransformer: AgentMsgTransformer,
                          wap: WalletAPIParam,
                          mw: MetricsWriter,
                          ec: ExecutionContext): Future[PackedMsg] = {
    mw.runWithSpan("buildRoutedAgentMsg", "AgentMsgPackagingUtil", InternalSpan) {
      if (fwdRoutes.isEmpty) Future.successful(packedMsg)
      else {
        buildFwdMsg(msgPackFormat, packedMsg, fwdRoutes, fwdMsgTypeVersion)
      }
    }
  }

  private def buildFwdMsg(msgPackFormat: MsgPackFormat,
                          packedMsg: PackedMsg,
                          fwdRoutes: List[FwdRouteMsg],
                          fwdMsgTypeVersion: String = MTV_1_0)
                          (implicit agentMsgTransformer: AgentMsgTransformer,
                           wap: WalletAPIParam,
                           ec: ExecutionContext): Future[PackedMsg] = {
    if (fwdRoutes.isEmpty) throw new RuntimeException("empty fwdRoutes not supported")
    buildFwdMsgForRoute(msgPackFormat, packedMsg, fwdRoutes.head, fwdMsgTypeVersion).flatMap { pm =>
      fwdRoutes match {
        case _ :: Nil  => Future.successful(pm)
        case _ :: tail => buildFwdMsg(msgPackFormat, pm, tail, fwdMsgTypeVersion)
        case _         => throw new RuntimeException("fwdRoutes not supported")
      }
    }
  }

  private def buildFwdMsgForRoute(msgPackFormat: MsgPackFormat,
                                  packedMsg: PackedMsg,
                                  fwdRoutes: FwdRouteMsg,
                                  fwdMsgTypeVersion: String = MTV_1_0)
                                 (implicit agentMsgTransformer: AgentMsgTransformer,
                                  wap: WalletAPIParam): Future[PackedMsg] = {
    val fwdMsg = buildFwdJsonMsg(
      msgPackFormat,
      fwdRoutes.to,
      packedMsg.msg,
      fwdMsgTypeVersion = fwdMsgTypeVersion,
      fwdMsgType = packedMsg.metadata.map(_.msgTypeStr)
    )
    fwdRoutes.encryptInfo.fold(
      si => agentMsgTransformer.packAsync(msgPackFormat, fwdMsg, EncryptParam(Set(si.keyParam), None)),
      ei => agentMsgTransformer.packAsync(msgPackFormat, fwdMsg, ei)
    )
  }

  /**
   * creates forward message as per given parameters
   * @param mpf message pack format
   * @param toDID DID to which the message should be routed to
   * @param msg packed message
   * @param fwdMsgTypeVersion forward message type version
   * @return
   */
  def buildFwdJsonMsg(mpf: MsgPackFormat,
                      toDID: DidStr,
                      msg: Array[Byte],
                      msgQualifier: MsgFamilyQualifier=EVERNYM_QUALIFIER,
                      msgName: MsgName=MSG_TYPE_FWD,
                      fwdMsgTypeVersion: String = MTV_1_0,
                      fwdMsgType: Option[String] = None): String = {
    mpf match {
      case MPF_MSG_PACK =>
        val fwdMsg = DefaultMsgCodec.toJson(
          FwdReqMsg_MFV_0_5(TypeDetail(MSG_TYPE_FWD, fwdMsgTypeVersion), toDID, msg, fwdMsgType)
        )
        buildBundledMsg(List(fwdMsg))
      case MPF_INDY_PACK =>
        val fwdJsValue = new JSONObject(new String(msg))
        val fwd = FwdReqMsg_MFV_1_0_1(
          typeStrFromMsgType(msgQualifier, MSG_FAMILY_ROUTING, fwdMsgTypeVersion, msgName),
          toDID,
          fwdJsValue,
          fwdMsgType
        )
        DefaultMsgCodec.toJson(fwd)
      case x            => throw new RuntimeException("unsupported msg pack format: " + x)
    }
  }

  def buildRoutingKeys(recipKey: VerKeyStr,
                       givenRoutingKeys: Seq[VerKeyStr]): Seq[VerKeyStr] = {
    if (givenRoutingKeys.contains(recipKey)) givenRoutingKeys
    else if (givenRoutingKeys.nonEmpty) Seq(recipKey) ++ givenRoutingKeys
    else givenRoutingKeys
  }

  /** routing by keys should be used before did exchange only
    * assumption here is that the 'to' field of forward message will contain the given routing key
    * and recipient of this message know how to route based on 'to' being a ver key
    *
    * @param msgPackFormat message packaging format (message pack or indy pack)
    * @param msg
    * @param routingKeys
    * @return
    */
  //TODO: come back to this and see if this requires any more refactoring
  def packMsgForRoutingKeys(msgPackFormat: MsgPackFormat,
                            msg: Array[Byte],
                            routingKeys: Seq[VerKeyStr],
                            msgType: String)
                           (implicit agentMsgTransformer: AgentMsgTransformer,
                            wap: WalletAPIParam,
                            mw: MetricsWriter,
                            ec: ExecutionContext): Future[PackedMsg] = {
    mw.runWithSpan("packMsgForRoutingKeys", "AgentMsgPackagingUtil", InternalSpan) {
      routingKeys.size match {
        case 0 => Future.successful(PackedMsg(msg))
        case 1 => throw new RuntimeException("insufficient routing keys: " + routingKeys)
        case _ =>
          val to = routingKeys.head
          val remaining = routingKeys.tail
          val encryptWith = remaining.head
          val fwdJsonMsg = buildFwdJsonMsg(MPF_INDY_PACK, to, msg,
            COMMUNITY_QUALIFIER, MSG_TYPE_FORWARD, fwdMsgType = Option(msgType))
          agentMsgTransformer.packAsync(msgPackFormat, fwdJsonMsg,
            EncryptParam(Set(KeyParam(Left(encryptWith))), None)).flatMap { newPackedMsg =>
            if (remaining.size >= 2) {
              packMsgForRoutingKeys(msgPackFormat, newPackedMsg.msg, remaining, msgType)
            } else {
              Future.successful(newPackedMsg)
            }
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

  def buildPackMsgParam(encryptInfo: EncryptParam, respMsgs: List[Any], wrapInBundledMsgs: Boolean):
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
