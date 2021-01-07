package com.evernym.verity.protocol.engine.urlShortening
import akka.actor.ActorSystem
import akka.pattern.ask
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.Main.akkActorResponseTimeout
import com.evernym.verity.actor.agent.msghandler.incoming.ControlMsg
import com.evernym.verity.config.AppConfig
import com.evernym.verity.protocol.engine.MsgBase
import com.evernym.verity.urlshortener.{DefaultURLShortener, UrlInfo, UrlShortened, UrlShorteningFailed, UrlShorteningResponse}

import scala.util.{Failure, Success}

class UrlShorteningAccessController(system: ActorSystem, appConfig: AppConfig) extends UrlShorteningAccess {

  def handleShortening(si: ShortenInviteRTM,
                       shortenedMsg: (String, String, String) => InviteShortenedRTM,
                       failedMsg: (String, String) => InviteShorteningFailedRTM,
                       handler: UrlShortenMsg => Unit): Unit = {
    system.actorOf(DefaultURLShortener.props(appConfig)) ? UrlInfo(si.inviteURL) onComplete {
      case Success(m) => m match {
        case UrlShortened(shortUrl) => handler(shortenedMsg(si.invitationId, si.inviteURL, shortUrl))
        case UrlShorteningFailed(_, msg) => handler(failedMsg(si.invitationId, msg))
      }
      case Failure(e) => handler(failedMsg(si.invitationId, e.getMessage))
    }
  }
}

//FIXME: -> RTM: Possibly refactor protocol message family to just use base type?
trait UrlShortenMsg extends MsgBase
trait ShortenInviteRTM extends UrlShortenMsg {
  def invitationId: String
  def inviteURL: String
}

trait InviteShortenedRTM extends UrlShortenMsg {
  def invitationId: String
  def longInviteUrl: String
  def shortInviteUrl: String
}

trait InviteShorteningFailedRTM extends UrlShortenMsg {
  def invitationId: String
  def reason: String
}

//trait dispose {
//
//  override def handleRelationshipShorteningInvite(si: RelShortenInvite)(handler: Option[ControlMsg] => Unit): Unit = {
//    system.actorOf(DefaultURLShortener.props(appConfig)) ? UrlInfo(si.inviteURL) onComplete {
//      case Success(m) => m match {
//        case UrlShortened(shortUrl) => handleCtl(RelInviteShortened(si.invitationId, si.inviteURL, shortUrl))(handler)
//        case UrlShorteningFailed(_, msg) => handleCtl(RelInviteShorteningFailed(si.invitationId, msg))(handler)
//      }
//      case Failure(e) => handleCtl(RelInviteShorteningFailed(si.invitationId, e.getMessage))(handler)
//    }
//  }
//
//  override def handleIssueCredShorteningInvite(si: IssueCredShortenInvite)(handler: Option[ControlMsg] => Unit): Unit = {
//    system.actorOf(DefaultURLShortener.props(appConfig)) ? UrlInfo(si.inviteURL) onComplete {
//      case Success(m) => m match {
//        case UrlShortened(shortUrl) => handleCtl(IssueCredInviteShortened(si.invitationId, si.inviteURL, shortUrl))(handler)
//        case UrlShorteningFailed(_, msg) => handleCtl(IssueCredInviteShorteningFailed(si.invitationId, msg))(handler)
//      }
//      case Failure(e) => handleCtl(IssueCredInviteShorteningFailed(si.invitationId, e.getMessage))(handler)
//    }
//  }
//
//  def testProof(si: PresentProofShortenInvite)(handler: Option[ControlMsg] => Unit): Unit = {
//    def a(m: Any): Unit = {
//      m match {
//        case UrlShortened(shortUrl) => handleCtl(PresentProofInviteShortened(si.invitationId, si.inviteURL, shortUrl))(handler)
//        case UrlShorteningFailed(_, msg) => handleCtl(PresentProofInviteShorteningFailed(si.invitationId, msg))(handler)
//      }
//    }
//    system.actorOf(DefaultURLShortener.props(appConfig)) ? UrlInfo(si.inviteURL) onComplete {
//      case Success(m) => a(m)
//      case Failure(e) => handleCtl(PresentProofInviteShorteningFailed(si.invitationId, e.getMessage))(handler)
//    }
//  }
//  override def handlePresentProofShorteningInvite(si: PresentProofShortenInvite)(handler: Option[ControlMsg] => Unit): Unit = {
//    system.actorOf(DefaultURLShortener.props(appConfig)) ? UrlInfo(si.inviteURL) onComplete {
//      case Success(m) => m match {
//        case UrlShortened(shortUrl) => handleCtl(PresentProofInviteShortened(si.invitationId, si.inviteURL, shortUrl))(handler)
//        case UrlShorteningFailed(_, msg) => handleCtl(PresentProofInviteShorteningFailed(si.invitationId, msg))(handler)
//      }
//      case Failure(e) => handleCtl(PresentProofInviteShorteningFailed(si.invitationId, e.getMessage))(handler)
//    }
//  }
//}