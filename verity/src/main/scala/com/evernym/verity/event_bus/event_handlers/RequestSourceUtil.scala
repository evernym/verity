package com.evernym.verity.event_bus.event_handlers

import com.evernym.verity.protocol.engine.{DomainId, PinstId, ProtoRef, RelationshipId, ThreadId}
import com.evernym.verity.protocol.engine.registry.PinstIdPair
import com.evernym.verity.protocol.protocols.protocolRegistry

import scala.util.matching.Regex

object RequestSourceUtil {

  def build(domainId: DomainId, relationshipId: RelationshipId, pinstId: PinstId, threadId: ThreadId, protoRef: ProtoRef): String = {
    s"$REQ_SOURCE_PREFIX/$domainId/$relationshipId/protocol/${protoRef.msgFamilyName}/${protoRef.msgFamilyVersion}/$pinstId?threadId=$threadId"
  }

  def extract(requestSourceStr: String): RequestSource = {
    requestSourceStr match {
      case REQ_SOURCE_REG_EX(domainId, relationshipId, protocol, version, pinstId, threadId) =>
        val protoRef = ProtoRef(protocol, version)
        protocolRegistry.find(protoRef) match {
          case Some(pe) => RequestSource(domainId, relationshipId, threadId, PinstIdPair(pinstId, pe.protoDef))
          case None     => throw new RuntimeException("[request-source-util] unsupported proto ref: " + protoRef)
        }
      case other => throw new RuntimeException("[request-source-util] unhandled request source: " + other)
    }
  }

  val REQ_SOURCE_PREFIX = "event-source://v1:ssi:protocol"
  val REQ_SOURCE_REG_EX: Regex = s"$REQ_SOURCE_PREFIX/(.*)/(.*)/protocol/(.*)/(.*)/(.*)\\?threadId=(.*)".r
}

case class RequestSource(domainId: DomainId, relationshipId: RelationshipId, threadId: ThreadId, pinstIdPair: PinstIdPair)