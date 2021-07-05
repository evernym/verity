package com.evernym.verity.actor.msgoutbox.outbox.dispatcher.base

import akka.actor.typed.scaladsl.{ActorContext, Behaviors, StashBuffer}
import akka.actor.typed.{ActorRef, Behavior}
import com.evernym.RetentionPolicy
import com.evernym.verity.actor.agent.MsgPackFormat.{MPF_INDY_PACK, MPF_PLAIN}
import com.evernym.verity.actor.msgoutbox.{ComMethod, Packaging}
import com.evernym.verity.actor.msgoutbox.adapters.{MsgStore, Transports, WalletOpExecutor}
import com.evernym.verity.actor.msgoutbox.message.MessageMeta.LegacyData
import com.evernym.verity.actor.msgoutbox.outbox._
import com.evernym.verity.actor.msgoutbox.outbox.dispatcher.base.MessageDispatcher.Commands.DeliverMsg
import com.evernym.verity.actor.msgoutbox.outbox.dispatcher.types.{BinaryPayloadParam, DIDComV1DispatchParam, DIDComV1Dispatcher, HttpTransportParam, IndyPackagingParam, PlainDispatchParam, PlainDispatcher, PlainPayloadParam}
import com.evernym.verity.constants.Constants.COM_METHOD_TYPE_HTTP_ENDPOINT
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.typesafe.scalalogging.Logger

import scala.concurrent.duration._


//ephemeral child actor (one for each 'outbox-id' and 'msg-id' combination)
// responsible to send the message to appropriate dispatchers based on
// 'packaging' or 'other needs'
object MessageDispatcher {

  trait Cmd
  object Commands {
    case class Initialize(msg: Msg) extends Cmd
    case class DeliverMsg(comMethodId: ComMethodId,
                          comMethod: ComMethod,
                          retryParam: Option[RetryParam],
                          replyTo: ActorRef[Outbox.Cmd]) extends Cmd
    case object Stop extends Cmd
  }

  case class Msg(`type`: String,
                 legacyData: Option[LegacyData],
                 packaging: Option[Packaging],
                 policy: RetentionPolicy,
                 payload: Option[Array[Byte]] = None)

  trait State

  object States {
    case object Uninitialized extends State
    case class Initializing(msg: Msg) extends State
    case class Initialized(msg: Msg) extends State
  }

  val logger: Logger = getLoggerByClass(getClass)

  def apply(msgId: MsgId,
            senderVerKey: VerKey,
            walletId: WalletId,
            walletOpExecutor: Behavior[WalletOpExecutor.Cmd],
            msgStore: ActorRef[MsgStore.Cmd],
            transports: Transports): Behavior[Cmd] = {
    Behaviors.withStash(10) { buffer => //TODO: finalize this
      Behaviors.setup { actorContext =>
        actorContext.setReceiveTimeout(120.seconds, Commands.Stop) //TODO: finalize this
        actorContext.spawn(MessageDispatcherCreator(msgId, msgStore, actorContext.self), msgId)
        initializing(msgId, senderVerKey, walletId, walletOpExecutor, transports)(buffer, actorContext)
      }
    }
  }

  private def initializing(msgId: MsgId,
                           senderVerKey: VerKey,
                           walletId: WalletId,
                           walletOpExecutor: Behavior[WalletOpExecutor.Cmd],
                           transports: Transports)
                          (implicit
                           buffer: StashBuffer[Cmd],
                           actorContext: ActorContext[Cmd]): Behavior[Cmd] = Behaviors.receiveMessage[Cmd] {

    case Commands.Initialize(msg) =>
      buffer.unstashAll(initialized(
        msgId,
        msg,
        senderVerKey,
        walletId,
        walletOpExecutor,
        transports)
      )

    case cmd =>
      buffer.stash(cmd)
      Behaviors.same
  }

  private def initialized(msgId: MsgId,
                          msg: Msg,
                          senderVerKey: VerKey,
                          walletId: WalletId,
                          walletOpExecutor: Behavior[WalletOpExecutor.Cmd],
                          transports: Transports)
                         (implicit actorContext: ActorContext[Cmd]): Behavior[Cmd] = Behaviors.receiveMessage[Cmd] {

    case dm: Commands.DeliverMsg =>
      if (dm.comMethod.typ == COM_METHOD_TYPE_HTTP_ENDPOINT &&
        dm.comMethod.packaging.exists(_.pkgType == MPF_INDY_PACK.toString)) {
        sendToDIDComV1Dispatcher(msgId, msg, dm, senderVerKey, walletId, walletOpExecutor, transports)
      } else if (dm.comMethod.typ == COM_METHOD_TYPE_HTTP_ENDPOINT &&
        dm.comMethod.packaging.exists(_.pkgType == MPF_PLAIN.toString)) {
        sendToPlainDispatcher(msgId, msg, dm, transports)
      } else {
        throw new RuntimeException("com method and/or packaging type not supported by message dispatcher: " + dm.comMethod.typ)
      }
      Behaviors.same

    case Commands.Stop =>
      if (actorContext.children.isEmpty) Behaviors.stopped
      else Behaviors.same
  }

  private def sendToDIDComV1Dispatcher(msgId: MsgId,
                                       msg: Msg,
                                       deliverMsg: DeliverMsg,
                                       senderVerKey: VerKey,
                                       walletId: WalletId,
                                       walletOpExecutor: Behavior[WalletOpExecutor.Cmd],
                                       transports: Transports)
                                      (implicit actorContext: ActorContext[Cmd]): Unit = {

    val uniqueKey = "didcomv1" + msgId + deliverMsg.comMethodId
    (msg.payload, actorContext.child(uniqueKey)) match {
      case (Some(payload), None) =>
        val dispatchParam = DIDComV1DispatchParam(
          msgId,
          deliverMsg.comMethodId,
          deliverMsg.retryParam,
          deliverMsg.replyTo,
          BinaryPayloadParam(payload),
          IndyPackagingParam(
            msg.packaging.getOrElse(
              deliverMsg.comMethod.packaging.getOrElse(
                throw new RuntimeException("packaging required but not supplied")
              )
            ),
            senderVerKey,
            walletId,
            walletOpExecutor
          ),
          HttpTransportParam(deliverMsg.comMethod.value, transports.httpTransporter)
        )
        val comMethodDispatcher = actorContext.spawn(DIDComV1Dispatcher(dispatchParam), uniqueKey)
        comMethodDispatcher ! DIDComV1Dispatcher.Commands.DeliverMsg
      case (_, Some(ar)) =>
        logger.info("DIDComV1 dispatcher is already running: " + ar)
      case other =>
        logger.info("unhandled condition while sending message to DIDComV1 dispatcher: " + other)
    }
  }

  private def sendToPlainDispatcher(msgId: MsgId,
                                    msg: Msg,
                                    deliverMsg: DeliverMsg,
                                    transports: Transports)
                                   (implicit actorContext: ActorContext[Cmd]): Unit = {

    val uniqueKey = "plain" + msgId + deliverMsg.comMethodId
    (msg.payload, actorContext.child(uniqueKey)) match {
      case (Some(payload), None) =>
        val dispatchParam = PlainDispatchParam(
          msgId,
          deliverMsg.comMethodId,
          deliverMsg.retryParam,
          deliverMsg.replyTo,
          PlainPayloadParam(new String(payload)),
          HttpTransportParam(deliverMsg.comMethod.value, transports.httpTransporter)
        )
        val comMethodDispatcher = actorContext.spawn(PlainDispatcher(dispatchParam), uniqueKey)
        comMethodDispatcher ! PlainDispatcher.Commands.DeliverMsg
      case (_, Some(ar)) =>
        logger.info("Plain dispatcher is already running: " + ar)
      case other =>
        logger.info("unhandled condition while sending message to Plain dispatcher: " + other)
    }
  }
}
