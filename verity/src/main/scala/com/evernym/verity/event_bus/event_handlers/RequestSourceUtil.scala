package com.evernym.verity.event_bus.event_handlers

import com.evernym.verity.protocol.engine.{PinstId, ProtoRef, ThreadId}
import com.evernym.verity.protocol.engine.registry.PinstIdPair
import com.evernym.verity.protocol.protocols.protocolRegistry
import com.evernym.verity.util2.RouteId

import scala.util.matching.Regex

object RequestSourceUtil {

  def build(domainUrlPrefix: String, routeId: RouteId, protoRef: ProtoRef, pinstId: PinstId, threadId: ThreadId): String = {
    s"$domainUrlPrefix/route/$routeId/protocol/${protoRef.msgFamilyName}/version/${protoRef.msgFamilyVersion}/pinstid/$pinstId?threadId=$threadId"
  }

  def extract(requestSourceStr: String): RequestSource = {
    requestSourceStr match {
      case REQ_SOURCE_REG_EX(prefix, route, protocol, version, pinstId, threadId) =>
        val protoRef = ProtoRef(protocol, version)
        protocolRegistry.find(protoRef) match {
          case Some(pe) => RequestSource(route, PinstIdPair(pinstId, pe.protoDef), threadId)
          case None     => throw new RuntimeException("[request-source-builder] unsupported proto ref: " + protoRef)
        }
      case other => throw new RuntimeException("[request-source-builder] unhandled request source: " + other)
    }
  }

  val REQ_SOURCE_REG_EX: Regex = "(.*)/route/(.*)/protocol/(.*)/version/(.*)/pinstid/(.*)\\?threadId=(.*)".r
}

case class RequestSource(route: RouteId, pinstIdPair: PinstIdPair, threadId: ThreadId)