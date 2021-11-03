package com.evernym.verity.did.didcomm.v1.messages

import com.evernym.verity.agentmsg.msgcodec.{InvalidMsgQualifierException, MsgTypeParsingException, UnrecognizedMsgQualifierException}
import com.evernym.verity.did.didcomm.v1.messages.MsgFamily.{MsgFamilyName, MsgFamilyQualifier, MsgFamilyVersion, MsgName}
import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByClass
import com.evernym.verity.protocol.engine.TypedMsg
import com.typesafe.scalalogging.Logger

import scala.util.matching.Regex

object MsgFamily {
  type MsgFamilyName = String
  type MsgFamilyVersion = String
  type MsgName = String
  type MsgTypeStr = String  //refactor this name to something better (we have a case class called MsgType and this was colliding with it)

  sealed trait MsgFamilyQualifier

  case object EvernymQualifier extends MsgFamilyQualifier

  case object CommunityQualifier extends MsgFamilyQualifier


  //TODO: Evernym qualifier given below is just a test string,
  // we'll have to decide what it should be?

  val QUALIFIER_FORMAT_HTTP = false

  val COMMUNITY_QUALIFIER: MsgFamilyQualifier = CommunityQualifier
  val EVERNYM_QUALIFIER: MsgFamilyQualifier = EvernymQualifier

  val VALID_MESSAGE_TYPE_REG_EX_HTTP: Regex = "(https)://(.*)/(.*)/(.*)/(.*)".r
  val VALID_MESSAGE_TYPE_REG_EX_DID: Regex = "did:(.*):(.*);spec/(.*)/(.*)/(.*)".r

  def msgQualifierFromQualifierStr(qualifier: String): MsgFamilyQualifier = {
    qualifier match {
      case "did:sov:BzCbsNYhMrjHiqZDTUASHg" => COMMUNITY_QUALIFIER
      case "did:sov:BzCbsNYhMrjHiqZDTUASHg;spec" => COMMUNITY_QUALIFIER
      case "BzCbsNYhMrjHiqZDTUASHg" => COMMUNITY_QUALIFIER
      case "did:sov:123456789abcdefghi1234" => EVERNYM_QUALIFIER
      case "did:sov:123456789abcdefghi1234;spec" => EVERNYM_QUALIFIER
      case "123456789abcdefghi1234" => EVERNYM_QUALIFIER
      case "didcomm.org" => COMMUNITY_QUALIFIER
      case "didcomm.evernym.com" => EVERNYM_QUALIFIER
      case _ => throw new UnrecognizedMsgQualifierException(qualifier)
    }
  }

  def qualifierStrFromMsgQualifier(msgFamilyQualifier: MsgFamilyQualifier): String = {
    msgFamilyQualifier match {
      case EVERNYM_QUALIFIER => if(QUALIFIER_FORMAT_HTTP) "didcomm.evernym.com" else "123456789abcdefghi1234"
      case COMMUNITY_QUALIFIER => if(QUALIFIER_FORMAT_HTTP) "didcomm.org" else "BzCbsNYhMrjHiqZDTUASHg"
      case _ => throw new InvalidMsgQualifierException
    }
  }

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
    if(QUALIFIER_FORMAT_HTTP) {  s"https://${qualifierStrFromMsgQualifier(familyQualifier)}/$familyName/$familyVersion/$msgName"
    } else s"did:sov:${qualifierStrFromMsgQualifier(familyQualifier)};spec/$familyName/$familyVersion/$msgName"
  }

  final def msgTypeFromTypeStr(typeStr: String): MsgType = {
    typeStr match {
      case VALID_MESSAGE_TYPE_REG_EX_DID(_, msgFamilyQualifier, msgFamily, msgFamilyVersion, msgType) =>
        MsgType(msgQualifierFromQualifierStr(msgFamilyQualifier), msgFamily, msgFamilyVersion, msgType)
      case VALID_MESSAGE_TYPE_REG_EX_HTTP(_, msgFamilyQualifier, msgFamily, msgFamilyVersion, msgType) =>
        MsgType(msgQualifierFromQualifierStr(msgFamilyQualifier), msgFamily, msgFamilyVersion, msgType)
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

//  lazy val protoRef = ProtoRef(name, version)
  lazy val logger: Logger = getLoggerByClass(getClass)

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

  override def toString: MsgFamilyName = s"$name[$version]"
}