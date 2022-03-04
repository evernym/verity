package com.evernym.verity.actor.typed.base

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import com.evernym.verity.actor.ActorMessage

import scala.collection.immutable
import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag

object Aggregator {

  sealed trait Cmd extends ActorMessage

  private case object ReceiveTimeout extends Cmd

  private case class WrappedReply[R](reply: R) extends Cmd

  def apply[Reply: ClassTag, Aggregate](sendRequests: ActorRef[Reply] => Unit,
                                        expectedReplies: Int,
                                        replyTo: ActorRef[Aggregate],
                                        aggregateReplies: immutable.IndexedSeq[Reply] => Aggregate,
                                        timeout: FiniteDuration): Behavior[Cmd] = {
    Behaviors.setup { context =>
      context.setReceiveTimeout(timeout, ReceiveTimeout)
      val replyAdapter = context.messageAdapter[Reply](WrappedReply(_))
      sendRequests(replyAdapter)

      @annotation.nowarn
      def collecting(replies: immutable.IndexedSeq[Reply]): Behavior[Cmd] = {
        Behaviors.receiveMessage {
          case WrappedReply(reply: Reply) =>
            val newReplies = replies :+ reply
            if (newReplies.size == expectedReplies) {
              val result = aggregateReplies(newReplies)
              replyTo ! result
              Behaviors.stopped
            } else
              collecting(newReplies)

          case ReceiveTimeout =>
            val aggregate = aggregateReplies(replies)
            replyTo ! aggregate
            Behaviors.stopped
        }
      }

      collecting(Vector.empty)
    }
  }

}
