package com.evernym.verity.protocol.engine

import com.evernym.verity.actor.agent.{MsgPackFormat, TypeFormat}
import com.evernym.verity.agentmsg.msgcodec.MsgTypeParsingException

import scala.util.matching.Regex


object MsgFamily {
  val EVERNYM_QUALIFIER: MsgFamilyQualifier = "123456789abcdefghi1234"

  val COMMUNITY_QUALIFIER: MsgFamilyQualifier = "BzCbsNYhMrjHiqZDTUASHg"

  val MSG_FAMILY_DID_METHOD: String = "sov"

  val VALID_MESSAGE_TYPE_REG_EX: Regex = "did:(.*):(.*);spec/(.*)/(.*)/(.*)".r

  def typeStrFromMsgType(msgType: MsgType): String = typeStrFromMsgType(
    msgType.familyQualifier,
    msgType.familyName,
    msgType.familyVersion,
    msgType.msgName
  )

  final def typeStrFromMsgType(fam: MsgFamily, msgName: MsgName): String = {
    typeStrFromMsgType(
      fam.qualifier,
      fam.name,
      fam.version,
      msgName
    )
  }

  final def typeStrFromMsgType(familyQualifier: MsgFamilyQualifier,
                               familyName: MsgFamilyName,
                               familyVersion: MsgFamilyVersion,
                               msgName: MsgName
                              ): String = {
    //note: if the string format below changes, we will need to change VALID_MESSAGE_TYPE_REG_EX above accordingly
    s"did:$MSG_FAMILY_DID_METHOD:$familyQualifier;spec/$familyName/$familyVersion/$msgName"
  }

  final def msgTypeFromTypeStr(typeStr: String): MsgType = {
    typeStr match {
      case VALID_MESSAGE_TYPE_REG_EX(_, msgFamilyQualifier, msgFamily, msgFamilyVersion, msgType) =>
        MsgType(msgFamilyQualifier, msgFamily, msgFamilyVersion, msgType)
      case _ =>
        throw new MsgTypeParsingException(typeStr)
    }
  }
}

trait MsgFamily {
  val qualifier: MsgFamilyQualifier
  val name: MsgFamilyName
  val version: MsgFamilyVersion

  protected val protocolMsgs: Map[MsgName, Class[_]]
  protected val controlMsgs: Map[MsgName, Class[_]] = Map.empty
  protected val signalMsgs: Map[Class[_], MsgName] = Map.empty

  lazy val protoRef = ProtoRef(name, version)

  private lazy val protocolMsgsReversed: Map[Class[_], MsgName] = protocolMsgs map (_.swap)
  private lazy val controlMsgsReversed: Map[Class[_], MsgName] = controlMsgs map (_.swap)

  lazy val allInputMsgs: Map[Class[_], MsgName] = protocolMsgsReversed ++ controlMsgsReversed
  lazy val allInputMsgsReversed: Map[MsgName, Class[_]] = allInputMsgs.map(_.swap)

  lazy val allOutputMsgs: Map[Class[_], MsgName] = protocolMsgsReversed ++ signalMsgs

  def lookupInputMsgName(cls: Class[_]): MsgName = allInputMsgs(cls)

  def lookupOutputMsgName(cls: Class[_]): MsgName = allOutputMsgs(cls)

  def lookupAllMsgName(cls: Class[_]): MsgName = (allInputMsgs ++ allOutputMsgs) (cls)

  def lookupClassOrElse(msgName: MsgName, orElse: => Class[_]): Class[_] =
    allInputMsgsReversed.getOrElse(msgName, (orElse _)() )

  lazy val msgNames: Set[MsgName] = protocolMsgs.keySet ++ controlMsgs.keySet

  def msgTypes: Set[MsgType] = msgNames map msgType
  def msgType(msgName: MsgName): MsgType = MsgType(qualifier, name, version, msgName)
  def msgType(cls: Class[_]): MsgType = msgType(lookupAllMsgName(cls))

  def typedMsg[A](msg: A): TypedMsg = {
    val msgType = MsgType(qualifier, name, version, lookupInputMsgName(msg.getClass))
    TypedMsg(msg, msgType)
  }

  def isControlMsg[A](msg: A): Boolean = controlMsgsReversed.contains(msg.getClass)
  def isProtocolMsg[A](msg: A): Boolean = protocolMsgsReversed.contains(msg.getClass)
  def isSignalMsg[A](msg: A): Boolean = signalMsgs.contains(msg.getClass)

  def msgCategory(msgName: String): Option[String] = {
    if (controlMsgs.contains(msgName)) Option("Ctl")
    else if (protocolMsgs.contains(msgName)) Option("Proto")
    else if (signalMsgs.map(_.swap).contains(msgName)) Option("Sig")
    else None
  }
}

/** Provides information which is needed during outgoing message flow (during packaging of the message)
  *
  * @param msgPackFormat         msg pack format (messagepack vs indypack) to use for message packaging
  *
  * @param msgTypeFormat          msg type format (legacy '@type' json object vs latest '@type' string), for example:
  *                               legacy: "@type":{"name":"MESSAGE","ver":"1.0","fmt":"json"}
  *                               latest: "@type":"did:sov:123456789abcdefghi1234;spec/TicTacToe/0.5/OFFER"
  *
  * @param useLegacyGenMsgWrapper puts a wrapper around the original json message, for example
  *                               {
  *                               "@type":{"name":"MESSAGE","ver":"1.0","fmt":"json"},
  *                               "@msg":"{"@type":"did:sov:123456789abcdefghi1234;spec/TicTacToe/0.5/OFFER","@id":"ac6cb167-ab1a-4d60-ac8f-059b3088e408", <original-msg-attributes>}"
  *                               }
  * @param useLegacyBundledMsgWrapper puts message into a bundled wrapper message, for example:
  *                                   {
  *                                     "bundled": [<packed-msg-1>, <packed-msg-2>]
  *                                   }
  *
  */
case class MsgPackagingContext(msgPackFormat: Option[MsgPackFormat]=None,
                               msgTypeFormat: Option[TypeFormat]=None,
                               useLegacyGenMsgWrapper: Boolean=false,
                               useLegacyBundledMsgWrapper: Boolean=false)
