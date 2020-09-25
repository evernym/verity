package com.evernym.verity.actor.agent.msghandler.incoming

import com.evernym.verity.actor.ActorMessageClass
import com.evernym.verity.actor.agent.msghandler.{MsgParam, MsgRespConfig}
import com.evernym.verity.agentmsg.msgcodec.{StandardTypeFormat, TypeFormat}
import com.evernym.verity.agentmsg.msgpacker.{AgentMessageWrapper, AgentMsgWrapper, PackedMsg}
import com.evernym.verity.protocol.engine.MsgFamily._
import com.evernym.verity.protocol.engine._
import com.evernym.verity.util.{ReqMsgContext, RestMsgContext}

/**
 *
 * @param givenMsg incoming message (see 'supportedTypes' function to know different types of supported incoming message)
 * @param msgType message type/family details
 */
case class IncomingMsgParam(givenMsg: Any, msgType: MsgType) extends MsgParam {

  override def supportedTypes: List[Class[_]] = List(classOf[AgentMsgWrapper], classOf[RestMsgParam])

  def senderVerKey: Option[VerKey] = givenMsg match {
    case amw: AgentMsgWrapper   => amw.senderVerKey
    case rmp: RestMsgParam      => Option(rmp.restMsgContext.auth.verKey)
  }

  def msgToBeProcessed: AgentMsgWrapper = givenMsg match {
    case amw: AgentMsgWrapper  => amw
    case rmp: RestMsgParam     => AgentMessageWrapper(rmp.msg, MPV_PLAIN)
  }

  def msgPackVersion: Option[MsgPackVersion] = givenMsg match {
    case amw: AgentMsgWrapper   => Option(amw.msgPackVersion)
    case _: RestMsgParam        => Option(MPV_PLAIN)
  }

  def msgFormat: Option[TypeFormat] = givenMsg match {
    case amw: AgentMsgWrapper   => Option(amw.headAgentMsg.msgTypeFormat)
    case _: RestMsgParam        => Option(StandardTypeFormat)
  }

  def usesLegacyGenMsgWrapper: Boolean = givenMsg match {
    case amw: AgentMsgWrapper   => amw.usesLegacyGenMsgWrapper
    case _: RestMsgParam        => false
  }

  def usesLegacyBundledMsgWrapper: Boolean = givenMsg match {
    case amw: AgentMsgWrapper   => amw.usesLegacyBundledMsgWrapper
    case _: RestMsgParam        => false
  }

  def msgPackVersionReq: MsgPackVersion = msgPackVersion.getOrElse(
    throw new RuntimeException("message pack version required, but not available")
  )

  def isSync(default: Boolean): Boolean = givenMsg match {
    case _: AgentMsgWrapper     => default
    case rmp: RestMsgParam      => rmp.restMsgContext.sync
  }
}

/**
 * this is used when control message is sent to user agent,
 * but the message is for a certain relationship,
 * @param msgToBeSent
 * @param threadId
 * @param msgPackVersion
 * @param msgTypeFormat
 */
case class MsgForRelationship[A](msgToBeSent: TypedMsgLike[A],
                                 threadId: ThreadId,
                                 senderParticipantId: ParticipantId,
                                 msgPackVersion: Option[MsgPackVersion],
                                 msgTypeFormat: Option[TypeFormat],
                                 msgRespConfig: Option[MsgRespConfig],
                                 reqMsgContext: Option[ReqMsgContext]=None
                                ) extends ActorMessageClass


case class PackedMsgParam(packedMsg: PackedMsg, reqMsgContext: ReqMsgContext) extends MsgBase with ActorMessageClass {
  override def validate(): Unit = {
    checkRequired("packedMsg", packedMsg)
  }
}

case class RestMsgParam(msg: String, restMsgContext: RestMsgContext) extends MsgBase with ActorMessageClass {
  override def validate(): Unit = {
    checkRequired("msg", msg)
  }
}

/**
 * below line is the sequence where 'this class (ControlMsg)' would be used/referenced
 * pinst -> signal -> actor driver -> agent actor -> Optional (ControlMsg -> pinst)
 * @param msg
 * @param forRel
 */
case class ControlMsg(msg: MsgBase, forRel: Option[DID]=None)

/**
 * a wrapper message containing the 'signalMsg' which is sent from the protocol and intercepted by the driver
 * and then driver sending it to corresponding agent actor.
 *
 * this is the sequence of where this class would be used/referenced
 * pinst -> signal -> actor driver -> agent actor -> signal msg handler
 *
 * @param signalMsg
 * @param threadId
 * @param protoRef
 * @param pinstId
 */
case class SignalMsgFromDriver(signalMsg: Any, threadId: ThreadId, protoRef: ProtoRef, pinstId: PinstId) extends ActorMessageClass


/*
 * THIS BELOW object IS STOPGAP WORKAROUND to support connect.me using vcx version 0.8.70229609
 *
 * VCX is sending an incorrect type for the 0.6 protocol messages but the message structure is
 * correct. This mapping allows use to support these 0.6 protocol messages even though they look
 * incorrect. This code is to compensate for a bug in VCX. This bug should/must be fixed in the next
 * release of VCX that connect.me uses and hopefully support for this version of VCX will be dropped.
 * Once that is true, this mapping can be dropped.
 */
object STOP_GAP_MsgTypeMapper {

  def changedMsgParam(imp: IncomingMsgParam): IncomingMsgParam = {
    changeMsgType(imp.msgType) match {
      case None             => imp
      case Some(newMsgType) =>
        val newGivenMsg = imp.givenMsg match {
          case amw: AgentMsgWrapper   =>
            changeMsgTypeInAgentMsgWrapper(amw, newMsgType)
          case rmp: RestMsgParam      =>
            RestMsgParam(rmp.msg, rmp.restMsgContext.copy(msgType = newMsgType))
        }
        IncomingMsgParam(newGivenMsg, newMsgType)
    }
  }

  def changeMsgTypeInAgentMsgWrapper(amw: AgentMsgWrapper, newMsgType: MsgType): AgentMsgWrapper = {
    val newAgentMsgs = amw.agentBundledMsg.msgs.map { am =>
      am.copy(msgFamilyDetail = am.msgFamilyDetail.copy(
        familyName = newMsgType.familyName, familyVersion = newMsgType.familyVersion, msgName = newMsgType.msgName))
    }
    val newAgentBundledMsg = amw.agentBundledMsg.copy(msgs = newAgentMsgs)
    amw.copy(agentBundledMsg = newAgentBundledMsg)
  }

  private def changeMsgType(msgType: MsgType): Option[MsgType] = msgType match {

    case mt @ MsgType(EVERNYM_QUALIFIER, "credential_exchange", "1.0", "credential-offer") =>
      Option(mt.copy(familyName = "issue-credential", familyVersion = "0.6", msgName="credOffer"))
    case mt @ MsgType(EVERNYM_QUALIFIER, "credential_exchange", "1.0", "credential-request") =>
      Option(mt.copy(familyName = "issue-credential", familyVersion = "0.6", msgName="credReq"))
    case mt @ MsgType(EVERNYM_QUALIFIER, "credential_exchange", "1.0", "credential") =>
      Option(mt.copy(familyName = "issue-credential", familyVersion = "0.6", msgName="cred"))

    case mt @ MsgType(EVERNYM_QUALIFIER, "credential_exchange", "1.0", "presentation-request") =>
      Option(mt.copy(familyName = "present-proof", familyVersion = "0.6", msgName="proofReq"))
    case mt @ MsgType(EVERNYM_QUALIFIER, "credential_exchange", "1.0", "presentation") =>
      Option(mt.copy(familyName = "present-proof", familyVersion = "0.6", msgName="proof"))

    case _ => None
  }
}
