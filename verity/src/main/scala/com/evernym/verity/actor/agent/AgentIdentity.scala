package com.evernym.verity.actor.agent

import com.evernym.verity.observability.logs.LoggerIdentity
import com.evernym.verity.protocol.engine.{DomainId, DomainIdFieldName}

import scala.util.Try

trait AgentIdentity extends LoggerIdentity {
  def domainId: DomainId

  def idTuplePair: (String, String) = (DomainIdFieldName, Try(domainId).getOrElse("unknown"))
}