package com.evernym.verity.actor.agent

import akka.actor.ActorRef
import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.did.DidStr

package object msgrouter {
  case class RouteAlreadySet(forDID: DidStr) extends ActorMessage
  case class RouteInfo(actorRef: ActorRef, entityId: String)
  case class ActorAddressDetail(actorTypeId: Int, address: String) extends ActorMessage

}
