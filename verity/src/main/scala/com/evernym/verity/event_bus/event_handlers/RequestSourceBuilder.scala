package com.evernym.verity.event_bus.event_handlers

import com.evernym.verity.event_bus.RequestSource
import com.evernym.verity.protocol.engine.ProtoRef
import com.evernym.verity.protocol.engine.registry.PinstIdPair
import com.evernym.verity.protocol.protocols.protocolRegistry

import scala.util.matching.Regex

object RequestSourceBuilder {

  def build(requestSourceStr: String): RequestSource = {
    requestSourceStr match {
      case REQ_SOURCE_REG_EX(prefix, route, protocol, version, pinstId) =>
        val protoRef = ProtoRef(protocol, version)
        protocolRegistry.find(protoRef) match {
          case Some(pe) => RequestSource(route, PinstIdPair(pinstId, pe.protoDef))
          case None     => throw new RuntimeException("[request-source-builder] unsupported proto ref: " + protoRef)
        }
      case other => throw new RuntimeException("[request-source-builder] unhandled request source: " + other)
    }
  }

  val REQ_SOURCE_REG_EX: Regex = "(.*)/route/(.*)/protocol/(.*)/version/(.*)/pinstid/(.*)".r
}
