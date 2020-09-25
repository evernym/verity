package com.evernym.verity.actor.agent

import com.evernym.verity.actor.agent.msgrouter.{ActorAddressDetail, SetRoute}
import com.evernym.verity.actor.persistence.AgentPersistentActor
import com.evernym.verity.protocol.engine.DID

import scala.concurrent.Future
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext


trait HasSetRoute { this: AgentPersistentActor =>

  /**
   * there are different types of actors (agency agent, agency pairwise, user agent and user agent pairwise)
   * when we store the persistence detail (as part of route), we also need to store these unique id (actor type id)
   * which then used during routing to know which type of region actor to be used to route the message
   * @return
   */
  def actorTypeId: Int

  /**
   *
   * @param forDID self/pairwise DID for which routing needs to be stored
   * @param agentKeyDID_DEPRECATED this is to make it backward compatible
   * @return Future
   */
  def setRoute(forDID: DID, agentKeyDID_DEPRECATED: Option[DID]=None): Future[Any] = {
    //NOTE: there is an issue with which DID we use during setting DID route
    //here is the ticket for more detail: VE-1108
    val routingDIDs = Set(forDID) ++ agentKeyDID_DEPRECATED
    val result = routingDIDs.map { targetDID =>
      agentActorContext.agentMsgRouter.execute(buildSetRoute(targetDID, actorTypeId, entityId))
    }
    Future.sequence(result).map(_.head)
  }

  def buildSetRoute(did: DID, actorTypeId: Int, entityId: String): SetRoute =
    SetRoute(did, ActorAddressDetail(actorTypeId, entityId))

}
