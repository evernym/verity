package com.evernym.verity.protocol.engine.urlShortening
import akka.actor.ActorSystem
import akka.pattern.ask
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.Main.akkActorResponseTimeout
import com.evernym.verity.config.AppConfig
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine.MsgBase
import com.evernym.verity.urlshortener.{DefaultURLShortener, UrlInfo, UrlShortened, UrlShorteningFailed}

import scala.util.{Failure, Success}

class UrlShorteningAccessController(system: ActorSystem, appConfig: AppConfig) extends UrlShorteningAccess {

  def handleShortening(si: ShortenInvite,
                       handler: UrlShortenMsg => Unit): Unit = {
    system.actorOf(DefaultURLShortener.props(appConfig)) ? UrlInfo(si.inviteURL) onComplete {
      case Success(m) => m match {
        case UrlShortened(shortUrl) => handler(InviteShortened(si.invitationId, si.inviteURL, shortUrl))
        case UrlShorteningFailed(_, msg) => handler(InviteShorteningFailed(si.invitationId, msg))
      }
      case Failure(e) => handler(InviteShorteningFailed(si.invitationId, e.getMessage))
    }
  }
}

trait UrlShortenMsg extends MsgBase
case class ShortenInvite(invitationId: String, inviteURL: String) extends UrlShortenMsg
case class InviteShortened(invitationId: String, longInviteUrl: String, shortInviteUrl: String) extends Control with UrlShortenMsg
case class InviteShorteningFailed(invitationId: String, reason: String) extends Control with UrlShortenMsg
