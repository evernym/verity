package com.evernym.verity.actor.agent

import akka.actor.ActorRef
import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.protocol.engine.DID

package object msgrouter {
  case class RouteAlreadySet(forDID: DID) extends ActorMessage
  case class RouteInfo(actorRef: ActorRef, entityId: String)
  case class ActorAddressDetail(actorTypeId: Int, address: String) extends ActorMessage

}
