package com.evernym.verity.protocol.legacy.services


import com.evernym.verity.did.{DID, DidPair}

import scala.concurrent.Future

/**
  * As part of connecting, edge agent first connects (exchanging keys) with given agent (can be agency agent or user agent)
  * 'setupCreateKeyEndpoint' implementation will do pairwise endpoint setup so that
  * edge agent can use it's pairwise endpoint and exchange further messages
  *
  */

trait CreateKeyEndpointServiceProvider {
  def setupCreateKeyEndpoint(forDID: DidPair, agentKeyDIDPair: DidPair, endpointDetailJson: String): Future[Any]
}

/**
  * As part of agent provisioning, edge agent wants to create a user agent endpoint.
  *
  * 'setupAgentEndpoint' implementation will do user agent endpoint setup so that
  * edge agent can use it's user agent endpoint and exchange further messages
  */


trait AgentEndpointServiceProvider {
  def setupNewAgentEndpoint(forDID: DidPair, agentKeyDIDPair: DidPair, endpointDetailJson: String): Future[Any]
}


case class CreateKeyEndpointDetail(regionTypeName: String,
                                   ownerDID: DID,
                                   ownerAgentKeyDidPair: Option[DidPair]=None,
                                   ownerAgentActorEntityId: Option[String]=None)

case class CreateAgentEndpointDetail(regionTypeName: String, entityId: String)