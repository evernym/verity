//package com.evernym.verity.actor.agent.outbox.poc
//
//import akka.Done
//import akka.actor.typed.{ActorRef, Behavior}
//import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
//import akka.pattern.StatusReply
//import akka.persistence.typed.PersistenceId
//import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, ReplyEffect}
//import com.evernym.verity.actor.agent.Thread
//import com.evernym.verity.actor.typed.Encodable
//import com.evernym.verity.protocol.engine.DID
//
//object Message {
//  type MsgId = String
//  type MsgType = String
//
//  //TODO: The Legacy Msg Type
//
//  /**
//   *
//   * @param typ        message type (for example: connReq, connReqAnswer etc)
//   * @param senderDID  message sender DID
//   * @param statusCode message status code (MS-101, MS-102 etc)
//   *                   see more status codes in Status.scala from line 63 to 69)
//   * @param refMsgId   optional, a referenced message Id (or replyMsgId), for example:
//   *                   if this was a 'credOffer' message, the 'refMsgId' will
//   *                   point to a 'credReq' message which is a reply of the 'credOffer' message)
//   * @param thread     optional, message thread
//   *
//   */
//  case class Msg(typ: String, senderDID: DID, statusCode: String, refMsgId: Option[String], thread: Option[Thread])
//
//
//  // Commands
//  sealed trait Cmd extends Encodable
//
//  object Cmd {
//    case class Set(msg: MsgType, replyTo: ActorRef[StatusReply[Done]]) extends Cmd
//
//    case class Get(replyTo: ActorRef[StatusReply[Reply.Msg]]) extends Cmd
//
//    case class Clear(replyTo: ActorRef[StatusReply[Done]]) extends Cmd
//
//    object Legacy {
//      //TODO: put legacy cmd msgs in here
//
//      /**
//       *
//       * @param refMsgId optional, a referenced message Id (or replyMsgId), for example:
//       *                 if this was a 'credOffer' message, the 'refMsgId' will
//       *                 point to a 'credReq' message which is a reply of the 'credOffer' message)
//       */
//      case class UpdateMsgRefId(refMsgId: MsgId) extends Cmd
//    }
//  }
//
//  // Replies
//  object Reply {
//    case class Msg(msgId: MsgId, msg: MsgType) extends Encodable
//  }
//
//  // Errors
//  object Error {
//    val MSG_NOT_SET = "message not set"
//    val MSG_ALREADY_SET = "message already set"
//    val MSG_CLEARED = "message cleared"
//  }
//
//  // Events
//  sealed trait Evt extends Encodable
//
//  object Evt {
//    case class Set(msg: MsgType) extends Evt
//
//    case object Cleared extends Evt
//  }
//
//  // States
//  sealed trait State extends Encodable
//
//  object State {
//    // empty state
//    case object Empty extends State
//
//    // state where a message is set
//    case class Msg(msg: MsgType) extends State
//
//    // state where a message was set, but is now cleared
//    case object Cleared extends State
//
//
//  }
//
//  // when used with sharding, this TypeKey can be used in `sharding.init` and `sharding.entityRefFor`
//  val TypeKey: EntityTypeKey[Cmd] = EntityTypeKey("Message")
//
//  def apply(msgId: MsgId): Behavior[Cmd] = {
//    EventSourcedBehavior
//      .withEnforcedReplies(PersistenceId(TypeKey.name, msgId),
//        State.Empty,
//        commandHandler(msgId),
//        eventHandler)
//  }
//
//  def commandHandler(msgId: MsgId): (State, Cmd) => ReplyEffect[Evt, State] = {
//
//    case (State.Empty, Cmd.Set(msg, replyTo)) => Effect.persist(Evt.Set(msg)).thenReply(replyTo)(_ => StatusReply.Ack)
//    case (State.Empty, Cmd.Get(replyTo)) => Effect.reply(replyTo)(StatusReply.error(Error.MSG_NOT_SET))
//    case (State.Empty, Cmd.Clear(replyTo)) => Effect.reply(replyTo)(StatusReply.error(Error.MSG_NOT_SET))
//
//    case (State.Msg(_), Cmd.Set(_, replyTo)) => Effect.reply(replyTo)(StatusReply.error(Error.MSG_ALREADY_SET))
//    case (State.Msg(m), Cmd.Get(replyTo)) => Effect.reply(replyTo)(StatusReply.success(Reply.Msg(msgId, m)))
//    case (State.Msg(_), Cmd.Clear(replyTo)) => Effect.persist(Evt.Cleared).thenReply(replyTo)(_ => StatusReply.Ack)
//
//    case (State.Cleared, Cmd.Set(_, replyTo)) => Effect.reply(replyTo)(StatusReply.error(Error.MSG_CLEARED))
//    case (State.Cleared, Cmd.Get(replyTo)) => Effect.reply(replyTo)(StatusReply.error(Error.MSG_CLEARED))
//    case (State.Cleared, Cmd.Clear(replyTo)) => Effect.reply(replyTo)(StatusReply.error(Error.MSG_CLEARED))
//
//    case (State.Empty, Cmd.Legacy.UpdateMsgRefId(_)) => ???
//    case (State.Msg(_), Cmd.Legacy.UpdateMsgRefId(_)) => ???
//    case (State.Cleared, Cmd.Legacy.UpdateMsgRefId(_)) => ???
//
//  }
//
//  // The match below allows for transitions that are not permitted based on the
//  // commandHandler above. But we are letting the Command Handler be
//  // restrictive and letting the state honor the events, independently.
//  private val eventHandler: (State, Evt) => State = {
//    case (_, Evt.Set(msg)) => State.Msg(msg)
//    case (_, Evt.Cleared) => State.Cleared
//  }
//
//
//}
