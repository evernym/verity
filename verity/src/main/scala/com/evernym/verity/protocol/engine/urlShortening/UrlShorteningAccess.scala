package com.evernym.verity.protocol.engine.urlShortening

import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine.MsgBase

trait UrlShorteningAccess {
  def shorten(si: ShortenInvite, handler: UrlShortenMsg => Unit): Unit
}

trait UrlShortenMsg extends MsgBase
case class ShortenInvite(invitationId: String, inviteURL: String) extends UrlShortenMsg
case class InviteShortened(invitationId: String, longInviteUrl: String, shortInviteUrl: String) extends Control with UrlShortenMsg
case class InviteShorteningFailed(invitationId: String, reason: String) extends Control with UrlShortenMsg
