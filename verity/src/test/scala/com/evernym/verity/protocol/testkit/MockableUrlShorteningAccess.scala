package com.evernym.verity.protocol.testkit

import com.evernym.verity.protocol.engine.urlShortening.{InviteShortened, InviteShorteningFailed, ShortenInvite, UrlShortenMsg, UrlShorteningAccess}

object MockableUrlShorteningAccess {
  def apply(): MockableUrlShorteningAccess = new MockableUrlShorteningAccess
  def shorteningFailed = new MockableUrlShorteningAccess(defaultUrlShorteningFailure)
  def shortened = new MockableUrlShorteningAccess(defaultUrlShorteningSuccess)

  def defaultUrlShorteningFailure: UrlShorteningAccess = (si: ShortenInvite, handler: UrlShortenMsg => Unit) =>
    handler(InviteShorteningFailed(si.invitationId, "because"))

  def defaultUrlShorteningSuccess: UrlShorteningAccess = (si: ShortenInvite, handler: UrlShortenMsg => Unit) =>
    handler(InviteShortened(si.invitationId, si.inviteURL, "http://short.url"))
}
class MockableUrlShorteningAccess(mockShortening: UrlShorteningAccess = MockableUrlShorteningAccess.defaultUrlShorteningSuccess)
  extends UrlShorteningAccess {

  override def handleShortening(si: ShortenInvite, handler: UrlShortenMsg => Unit): Unit =
    mockShortening.handleShortening(si, handler)
}
