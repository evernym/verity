package com.evernym.verity.protocol.engine.asyncapi.urlShorter

import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine.MsgBase
import com.evernym.verity.urlshortener.UrlShorteningResponse

import scala.util.Try

trait UrlShorteningAccess {
  def shorten(longUrl: String)(handler: Try[UrlShorteningResponse] => Unit): Unit
}

case class ShortenInvite(invitationId: String, inviteURL: String)
case class InviteShortened(longInviteUrl: String, shortInviteUrl: String) extends Control with MsgBase
