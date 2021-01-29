package com.evernym.verity.protocol.testkit

import com.evernym.verity.protocol.engine.urlShortening.{InviteShortened, UrlShorteningAccess}

import scala.util.{Failure, Success, Try}

object MockableUrlShorteningAccess {
  def apply(): MockableUrlShorteningAccess = new MockableUrlShorteningAccess
  def shorteningFailed = new MockableUrlShorteningAccess(defaultUrlShorteningFailure)
  def shortened = new MockableUrlShorteningAccess(defaultUrlShorteningSuccess)

  def defaultUrlShorteningFailure: UrlShorteningAccess = new UrlShorteningAccess {
    override def shorten(inviteUrl: String)(handler: Try[InviteShortened] => Unit): Unit =
      handler(Failure(new Exception("because")))
  }

  def defaultUrlShorteningSuccess: UrlShorteningAccess = new UrlShorteningAccess {
    override def shorten(inviteUrl: String)(handler: Try[InviteShortened] => Unit): Unit =
      handler(Success(InviteShortened(inviteUrl, "http://short.url")))
  }
}
class MockableUrlShorteningAccess(mockShortening: UrlShorteningAccess = MockableUrlShorteningAccess.defaultUrlShorteningSuccess)
  extends UrlShorteningAccess {

  override def shorten(inviteUrl: String)(handler: Try[InviteShortened] => Unit): Unit =
    mockShortening.shorten(inviteUrl)(handler)
}
