package com.evernym.verity.protocol.engine.urlShortening

trait UrlShorteningAccess {
  def handleShortening(si: ShortenInviteRTM,
                       shortenedMsg: (String, String, String) => InviteShortenedRTM,
                       failedMsg: (String, String) => InviteShorteningFailedRTM,
                       handler: UrlShortenMsg => Unit): Unit
}
