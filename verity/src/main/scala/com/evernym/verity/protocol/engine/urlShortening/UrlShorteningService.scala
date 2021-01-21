package com.evernym.verity.protocol.engine.urlShortening

import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine.MsgBase
import com.evernym.verity.protocol.engine.asyncProtocol.AsyncProtocolService

trait UrlShorteningService extends AsyncProtocolService[UrlShortenMsg] {
  def shorten(si: ShortenInvite)(handler: AsyncHandler): Unit
}

trait UrlShortenMsg extends MsgBase
case class ShortenInvite(invitationId: String, inviteURL: String) extends UrlShortenMsg
case class InviteShortened(invitationId: String, longInviteUrl: String, shortInviteUrl: String) extends Control with UrlShortenMsg
case class InviteShorteningFailed(invitationId: String, reason: String) extends Control with UrlShortenMsg
