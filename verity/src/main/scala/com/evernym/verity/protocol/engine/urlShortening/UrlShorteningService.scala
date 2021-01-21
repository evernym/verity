package com.evernym.verity.protocol.engine.urlShortening

import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine.MsgBase

import scala.util.Try

trait UrlShorteningService {
  def shorten(inviteUrl: String)(handler: Try[InviteShortened] => Unit): Unit
}

case class ShortenInvite(invitationId: String, inviteURL: String)
case class InviteShortened(longInviteUrl: String, shortInviteUrl: String) extends Control with MsgBase
