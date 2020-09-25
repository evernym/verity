package com.evernym.verity.actor.agent

import com.evernym.verity.protocol.engine.DomainId

trait AgentIdentity {
  def domainId: DomainId
}
