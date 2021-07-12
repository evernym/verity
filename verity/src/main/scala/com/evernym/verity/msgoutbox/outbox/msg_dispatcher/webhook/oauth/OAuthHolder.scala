//package com.evernym.verity.msgoutbox.outbox.msg_dispatcher.webhook.oauth
//
//import akka.actor.typed.{ActorRef, Behavior}
//import akka.actor.typed.scaladsl.{ActorContext, Behaviors, StashBuffer}
//import com.evernym.verity.msgoutbox.outbox.msg_dispatcher.webhook.oauth.OAuthHolder.Commands.{GetRefreshedToken, GetToken, UpdateToken}
//import com.evernym.verity.msgoutbox.outbox.msg_dispatcher.webhook.oauth.OAuthHolder.Replies.Token
//
//import java.time.LocalDateTime
//
//object OAuthHolder {
//
//  trait Cmd
//  object Commands {
//    case class Init() extends Cmd
//    case class GetToken(replyTo: ActorRef[Reply]) extends Cmd
//    case class GetRefreshedToken(replyTo: ActorRef[Reply]) extends Cmd
//
//    case class UpdateToken(tokenParam: TokenParam) extends Cmd
//  }
//
//  trait Reply
//  object Replies {
//    case class Token(value: String) extends Reply
//  }
//
//  def apply(): Behavior[Cmd] = {
//    Behaviors.setup { actorContext =>
//      Behaviors.withStash(10) { buffer =>
//        initialized(None)(actorContext, buffer)
//      }
//    }
//  }
//
//  def initialized(authToken: Option[TokenParam])
//                 (implicit actorContext: ActorContext[Cmd],
//                  buffer: StashBuffer[Cmd]): Behavior[Cmd] = Behaviors.receiveMessage {
//    case cmd @ GetToken(replyTo) =>
//      authToken match {
//        case Some(at) if ! at.isExpired =>
//          replyTo ! Token(at.value)
//          Behaviors.same
//        case _ =>
//          buffer.stash(cmd)
//          refreshToken()
//          Behaviors.same
//      }
//
//    case GetRefreshedToken(replyTo) =>
//      buffer.stash(GetToken(replyTo))
//      refreshToken()
//      Behaviors.same
//
//    case UpdateToken(tp) =>
//      buffer.unstashAll(initialized(Option(tp)))
//  }
//
//  def refreshToken()(implicit actorContext: ActorContext[Cmd],
//                     buffer: StashBuffer[Cmd]): Unit = {
//    //TODO: update this logic
//    actorContext.self ! UpdateToken(TokenParam("dummy", LocalDateTime.now()))
//  }
//}
//
//case class TokenParam(value: String, refreshedAt: LocalDateTime) {
//  def isExpired: Boolean = true   //TODO: apply logic
//}
