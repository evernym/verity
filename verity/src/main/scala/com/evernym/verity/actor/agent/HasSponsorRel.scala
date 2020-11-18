package com.evernym.verity.actor.agent

import com.evernym.verity.protocol.engine.DomainId

import scala.concurrent.Future

trait HasSponsorRel {
  def getSponsorRel(domainId: DomainId): Future[Option[SponsorRel]]
}
