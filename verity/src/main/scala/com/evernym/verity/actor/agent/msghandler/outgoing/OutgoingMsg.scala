package com.evernym.verity.actor.agent.msghandler.outgoing

import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.actor.agent.msghandler.MsgParam
import com.evernym.verity.actor.agent.user.ComMethodDetail
import com.evernym.verity.protocol.engine._
import com.evernym.verity.actor.agent.{PayloadMetadata, ThreadContextDetail}
import com.evernym.verity.actor.wallet.PackedMsg
import com.evernym.verity.did.didcomm.v1.messages.MsgId
import com.evernym.verity.push_notification.PushNotifData

import scala.concurrent.Future

trait RequestMsgIdProvider {
  def requestMsgId: Option[MsgId]
}

/**
 *
 * @param to recipient participant id
 * @param from sender participant id
 * @param requestMsgId request msg id for which to which the outgoing message is related to
 */
case class OutgoingMsgContext(to: ParticipantId,
                              from: ParticipantId,
                              threadContextDetail: ThreadContextDetail,
                              requestMsgId: Option[MsgId]) extends RequestMsgIdProvider

object OutgoingMsg {
  def apply[A](msg: A,
               to: ParticipantId,
               from: ParticipantId,
               pinstId: PinstId,
               protoDef: ProtoDef,
               threadContextDetail: ThreadContextDetail,
               requestMsgId: Option[MsgId]): OutgoingMsg[A] = {
    OutgoingMsg(msg, pinstId, protoDef, OutgoingMsgContext(to, from, threadContextDetail, requestMsgId))
  }
}

case class OutgoingMsg[A](msg: A,
                          pinstId: PinstId,
                          protoDef: ProtoDef,
                          context: OutgoingMsgContext)

/**
 * This is used by actor driver to send a signal message to agent's registered endpoint
 * @param msg - native message which needs to be sent to endpoint
 * @param threadId - thread id
 * @param protoRef - protocol reference
 * @param pinstId - protocol instance id
 */
case class SendSignalMsg(msg: Any,
                         threadId: ThreadId,
                         protoRef: ProtoRef,
                         pinstId: PinstId,
                         threadContextDetail: ThreadContextDetail,
                         requestMsgId: Option[MsgId]) extends ActorMessage

/**
 * This case class is used by GenericProtocolActor to send synchronous response message
 * We still have existing protocols which relies on synchronous response, although
 * now we can migrate them to send asynchronous response (by using protocol context's 'send' method),
 * but until we migrate them all, GenericProtocolActor will have to send response messages back
 */
case class ProtocolSyncRespMsg(msg: Any, requestMsgId: Option[MsgId]) extends RequestMsgIdProvider with ActorMessage


/**
 * interface to send messages (implemented by various traits in MsgNotifier.scala)
 */
trait SendOutgoingMsg {

  /**
   * responsible to send stored message to self's edge agent
   * (for example by using push notification and/or http endpoint etc)
   * @param msgId stored message id
   */
  def sendStoredMsgToSelf(msgId: MsgId): Future[Any] = {
    Future.successful("default implementation")
  }

  /**
   * sends push notification to given com methods
   * to given com methods
   * @param pnData push notification data
   * @param pcms push com methods values
   * @param sponsorId sponsor Id
   * @return
   */
  def sendPushNotif(pcms: Set[ComMethodDetail], pnData: PushNotifData, sponsorId: Option[String]): Future[Any]

}


/**
 * This case class is used by this actor itself to send this message to self
 * to make sure once the msg is stored (successful persistence) then it tries
 * to send it to edge agent
 */
case class SendStoredMsgToMyDomain(msgId: MsgId) extends ActorMessage

case class JsonMsg(msg: String) extends ActorMessage

/**
 * final outgoing message (packed or plain) to be stored and/or sent
 * @param givenMsg outgoing message
 * @param metadata optional metadata about the message
 */
case class OutgoingMsgParam(givenMsg: Any, metadata: Option[PayloadMetadata]=None) extends MsgParam {
  override def supportedTypes: List[Class[_]] = List(classOf[PackedMsg], classOf[JsonMsg])

  def msgToBeProcessed: Array[Byte] = givenMsg match {
    case pm: PackedMsg   => pm.msg
    case jm: JsonMsg     => jm.msg.getBytes()
    case other           => throw new RuntimeException("Unsupported outgoing msg: " + other.getClass.getSimpleName)
  }

  def jsonMsg_!(): String = givenMsg match {
    case jm: JsonMsg     => jm.msg
    case other           => throw new RuntimeException("Unsupported outgoing json message: " + other.getClass.getSimpleName)
  }
}
