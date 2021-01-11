package com.evernym.verity.protocol.engine.urlShortening

trait UrlShorteningAccess {
  def handleShortening(si: ShortenInvite, handler: UrlShortenMsg => Unit): Unit
}
