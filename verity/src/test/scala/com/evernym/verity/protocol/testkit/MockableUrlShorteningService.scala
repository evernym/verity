package com.evernym.verity.protocol.testkit

import com.evernym.verity.protocol.engine.urlShortening.{InviteShortened, InviteShorteningFailed, ShortenInvite, UrlShortenMsg, UrlShorteningService}

object MockableUrlShorteningService {
  def apply(): MockableUrlShorteningService = new MockableUrlShorteningService
  def shorteningFailed = new MockableUrlShorteningService(defaultUrlShorteningFailure)
  def shortened = new MockableUrlShorteningService(defaultUrlShorteningSuccess)

  def defaultUrlShorteningFailure: UrlShorteningService = new UrlShorteningService {
    override def shorten(si: ShortenInvite)(handler: AsyncHandler): Unit =
      handler(InviteShorteningFailed(si.invitationId, "because"))
  }

  def defaultUrlShorteningSuccess: UrlShorteningService = new UrlShorteningService {
    override def shorten(si: ShortenInvite)(handler: AsyncHandler): Unit =
      handler(InviteShortened(si.invitationId, si.inviteURL, "http://short.url"))
  }
}
class MockableUrlShorteningService(mockShortening: UrlShorteningService = MockableUrlShorteningService.defaultUrlShorteningSuccess)
  extends UrlShorteningService {

  override def shorten(si: ShortenInvite)(handler: UrlShortenMsg => Unit): Unit =
    mockShortening.shorten(si)(handler)
}
