package com.evernym.verity.protocol.testkit

import com.evernym.verity.protocol.engine.urlShortening.{InviteShortened, UrlShorteningService}

import scala.util.{Failure, Success, Try}

object MockableUrlShorteningService {
  def apply(): MockableUrlShorteningService = new MockableUrlShorteningService
  def shorteningFailed = new MockableUrlShorteningService(defaultUrlShorteningFailure)
  def shortened = new MockableUrlShorteningService(defaultUrlShorteningSuccess)

  def defaultUrlShorteningFailure: UrlShorteningService = new UrlShorteningService {
    override def shorten(inviteUrl: String)(handler: Try[InviteShortened] => Unit): Unit =
      handler(Failure(new Exception("because")))
  }

  def defaultUrlShorteningSuccess: UrlShorteningService = new UrlShorteningService {
    override def shorten(inviteUrl: String)(handler: Try[InviteShortened] => Unit): Unit =
      handler(Success(InviteShortened(inviteUrl, "http://short.url")))
  }
}
class MockableUrlShorteningService(mockShortening: UrlShorteningService = MockableUrlShorteningService.defaultUrlShorteningSuccess)
  extends UrlShorteningService {

  override def shorten(inviteUrl: String)(handler: Try[InviteShortened] => Unit): Unit =
    mockShortening.shorten(inviteUrl)(handler)
}
