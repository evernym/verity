package com.evernym.verity.actor.agent.outbox

import akka.Done
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityTypeKey}
import akka.pattern.StatusReply
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, ReplyEffect, RetentionCriteria}
import com.evernym.verity.actor.agent.outbox.Message.MsgId
import org.scalatest.time.SpanSugar.convertIntToGrainOfTime


object Outbox {
  type OutboxId = String

  /** This is the mechanism by which an Outbox limits the number of messages
    * with which it replies in Reply.Msgs when it receives a Cmd.GetMsgs. The
    * `max` field of Cmd.GetMsgs could further limit the number of messages
    * returned; the number of messages returned in Reply.Msgs will be the
    * smaller of this value and the max field in Cmd.GetMsgs if provided.
    */
  val getMsgsMax = 10

  // Commands
  sealed trait Cmd extends Encodable
  object Cmd {
    case class AddMsg(msgId: MsgId, replyTo: ActorRef[StatusReply[Done]]) extends Cmd
    case class GetMsgs(replyTo: ActorRef[StatusReply[Reply.Msgs]], max: Option[Int]=None) extends Cmd
    case class Received(thruSeqNr: Int, replyTo: ActorRef[StatusReply[Done]]) extends Cmd
    object Legacy {
      //TODO: put legacy cmd msgs in here
      /**
       *
       * @param msgId a unique msgId for the message
       * @param statusCode message status code (MDS-101, MDS-102 etc)
       *                   see more status codes in Status.scala from line 78 to 80)
       * @param sendMsg a boolean to determine if the message is supposed to be sent to other's domain
       */
      case class AddMsg(msgId: MsgId, statusCode: String, sendMsg: Boolean=true) extends Cmd

      /**
       *
       * @param msgId a unique msgId for the message
       * @param statusCode message status code (MDS-101, MDS-102 etc)
       *                   see more status codes in Status.scala from line 78 to 80)
       */
      case class UpdateDeliveryStatus(msgId: MsgId, statusCode: String) extends Cmd

      /**
       * filter messages based on given criteria and send it back
       * the 'uids' and 'statusCodes' are treated as logical 'AND' operator
       *
       * @param uids filter messages whose message id is one of these
       * @param statusCodes  filter messages whose status code is one of these
       * @param includePayload determines, if in the response, the 'message' needs be included
       *                       or just metadata needs to be sent
       */
      case class GetMsgs(uids: Set[MsgId], statusCodes: Set[String], includePayload: Boolean) extends Cmd
    }
  }


  // Replies
  object Reply {

    /**
      * Holds a collection of messages.
      * @param msgs ordered collection of messages
      * @param thruSeqNr sequence number of the last item in the collection,
      *                  can be used in conjunction with Received command to
      *                  indicate all messages have been received and
      *                  processed.
      */
    case class Msgs(msgs: IndexedSeq[Message.Reply.Msg], thruSeqNr: Int)
  }

  // Errors
  object Error {
    val INVALID_THRU_SEQ_NR = "invalid thruSeqNr"
  }

  // Events
  sealed trait Evt extends Encodable
  object Evt {
    case class MsgAdded(msgId: MsgId) extends Evt
    case class SetReceivedSeqNr(seqNr: Int) extends Evt
  }

  // States
  sealed trait State extends Encodable {
    def applyEvent(event: Evt): State
    def numPending: Int
  }

  object State {

    case object Empty extends State {
      def applyEvent(evt: Evt): State = evt match {
        case Evt.MsgAdded(msgId) => Basic(Vector(msgId))
      }

      val numPending: Int = 0
    }

    case class Basic(all: Vector[MsgId] = Vector.empty, receivedSeqNr: Int = 0) extends State {

      def applyEvent(evt: Evt): State = evt match {
        case Evt.MsgAdded(msgId) => copy(all = all :+ msgId)
        case Evt.SetReceivedSeqNr(seqNr) => copy(receivedSeqNr=seqNr)
      }

      def pending(): (Vector[MsgId], Int) = includeSeqNr { all.drop(receivedSeqNr) }
      def pending(n: Int): (Vector[MsgId], Int) = includeSeqNr { all.slice(receivedSeqNr, receivedSeqNr + n) }
      def delivered: Vector[MsgId] = all.take(receivedSeqNr)

      def numPending: Int = all.length - receivedSeqNr

      private def includeSeqNr(msgIds: Vector[MsgId]): (Vector[MsgId], Int) = {
        val thruSeqNr = receivedSeqNr + msgIds.length
        (msgIds,thruSeqNr)
      }

    }
  }

  // when used with sharding, this TypeKey can be used in `sharding.init` and `sharding.entityRefFor`
  val TypeKey: EntityTypeKey[Cmd] = EntityTypeKey("Outbox")

  def apply(outboxId: OutboxId): Behavior[Cmd] = {
    Behaviors.setup { context =>
      EventSourcedBehavior
        .withEnforcedReplies(PersistenceId(TypeKey.name, outboxId),
          State.Empty,
          commandHandler(context, outboxId),
          eventHandler)
        .withRetention {
          RetentionCriteria.snapshotEvery(
            numberOfEvents = 10,
            keepNSnapshots = 100  //TODO: increased from '2' to higher value to make failing test working, need to come back to this
          )
        }
    }
  }

  def commandHandler(context: ActorContext[Cmd], outboxId: OutboxId): (State, Cmd) => ReplyEffect[Evt, State] = { (state, cmd) =>
    lazy val sharding = ClusterSharding(context.system)
    (state, cmd) match {
      case (State.Empty | _: State.Basic, c: Cmd.AddMsg) =>
        context.log.info(s"Outbox $outboxId received $c")
        Effect.persist(Evt.MsgAdded(c.msgId)).thenReply(c.replyTo)(_ => StatusReply.Ack)

      // no pending messages
      case (s: State.Basic, c: Cmd.GetMsgs) if s.numPending <= 0 => ???

      // pending messages exist
      case (s: State.Basic, c: Cmd.GetMsgs) =>
        val limit = c.max
          .map(m => if (m < getMsgsMax) m else getMsgsMax)
          .getOrElse(getMsgsMax)
        val (msgIds, thruSeqNr) = s.pending(limit)
        spawnAggregator(context, sharding, c.replyTo, msgIds, thruSeqNr)
        Effect.noReply

      case (s: State.Basic, c: Cmd.Received) if c.thruSeqNr == s.receivedSeqNr => Effect.reply(c.replyTo)(StatusReply.Ack)
      case (s: State.Basic, c: Cmd.Received) if c.thruSeqNr <= s.receivedSeqNr => Effect.reply(c.replyTo)(StatusReply.error(Error.INVALID_THRU_SEQ_NR))
      case (_: State.Basic, c: Cmd.Received) => Effect.persist(Evt.SetReceivedSeqNr(c.thruSeqNr)).thenReply(c.replyTo)(_ => StatusReply.Ack)

    }
  }

  private val eventHandler: (State, Evt) => State = {
    (state, event) => state.applyEvent(event)
  }

  //TODO what happens if the Message actors never respond to the Aggregator
  def spawnAggregator( context: ActorContext[Cmd],
                       sharding: ClusterSharding,
                       replyTo: ActorRef[StatusReply[Reply.Msgs]],
                       msgIds: Vector[MsgId],
                       thruSeqNr: Int): Unit = {

    // this requests messages from all of the message actors
    val sendRequests: ActorRef[StatusReply[Message.Reply.Msg]] => Unit = {
      replyTo => {
        msgIds foreach {
          sharding.entityRefFor(Message.TypeKey, _) ! Message.Cmd.Get(replyTo)
        }
      }
    }

    // after replies are received, this combines them so they can be sent to 'replyTo'
    val aggregateReplies: IndexedSeq[StatusReply[Message.Reply.Msg]] => StatusReply[Outbox.Reply.Msgs] = {
      replies => {

        // TODO call to _.getValue assumes StatusReplys are all success; it probably shouldn't
        val values = replies.map(_.getValue)

        // need to make sure the replies are sorted in the same order as msgIds
        val msgOrdering = msgIds.zipWithIndex.toMap
        val sorted = values sortWith {
          (a, b) => msgOrdering(a.msgId) < msgOrdering(b.msgId)
        }

        StatusReply.success(Reply.Msgs(sorted, thruSeqNr))

      }
    }

    context spawnAnonymous {
      Aggregator[StatusReply[Message.Reply.Msg], StatusReply[Reply.Msgs]](
        sendRequests,
        expectedReplies = msgIds.length,
        replyTo,
        aggregateReplies,
        timeout = 5.seconds)
    }
  }


}
