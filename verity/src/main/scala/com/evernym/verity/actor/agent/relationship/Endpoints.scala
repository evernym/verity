package com.evernym.verity.actor.agent.relationship

import com.evernym.verity.actor.agent.relationship.AuthorizedKeys.KeyId
import com.evernym.verity.actor.agent.relationship.Endpoints.EndpointId
import com.evernym.verity.actor.agent.relationship.Relationship.URL
import com.evernym.verity.protocol.engine.{DID, MsgPackVersion, VerKey}


object Endpoints {
  type EndpointId = String    //a unique id for the given endpoint

  def empty: Endpoints = Endpoints(Vector.empty, Map.empty)

  /**
   *
   * @param endpoint endpoint to be added
   * @param authKeys auth keys belonging to provided endpoint
   * @return
   */
  def init(endpoint: EndpointLike, authKeys: Set[KeyId]): Endpoints =
    init(Vector(endpoint), authKeys)

  /**
   *
   * @param endpoints endpoints to be added
   * @param authKeys auth keys belonging to all given endpoints
   * @return
   */
  def init(endpoints: Vector[EndpointLike], authKeys: Set[KeyId]): Endpoints = {
    val endpointsToAuthKeys = endpoints.map(ep => ep.id -> authKeys).toMap
    Endpoints(endpoints, endpointsToAuthKeys)
  }
}

/**
 *
 * @param endpoints collection of different endpoints
 * @param endpointsToAuthKeys mapping between endpoint id and auth key ids
 */
case class Endpoints(endpoints: Vector[EndpointLike], endpointsToAuthKeys: Map[EndpointId, Set[KeyId]]) {

  validate()

  def validate() {

    endpointsToAuthKeys.keySet.find(eid => ! endpoints.map(_.id).contains(eid)).map { eid =>
      throw new RuntimeException(s"endpoint with id '$eid' not exists")
    }

    if (endpoints.map(_.value).toSet.size != endpoints.size) {
      throw new RuntimeException("endpoint with same 'value' not allowed")
    }

    if (endpoints.map(_.id).toSet.size != endpoints.size) {
      throw new RuntimeException("endpoints with same 'id' not allowed")
    }

    if (endpoints.exists(ep => !endpointsToAuthKeys.contains(ep.id))) {
      throw new RuntimeException("endpoints without auth key mapping not allowed")
    }
  }

  def filterByKeyIds(keyIds: KeyId*): Vector[EndpointLike] = {
    val endpointIds = endpointsToAuthKeys.filter(_._2.intersect(keyIds.toSet).nonEmpty).keySet
    endpoints.filter(ep => endpointIds.contains(ep.id))
  }

  def filterByTypes(types: Int*): Vector[EndpointLike] =
    endpoints.filter(ep => types.toVector.contains(ep.`type`))

  def filterByValues(values: String*): Vector[EndpointLike] =
    endpoints.filter(ep => values.toVector.contains(ep.value))

  def findById(id: EndpointId): Option[EndpointLike] = endpoints.find(_.id == id)

  def addOrUpdate(endpoint: EndpointLike, authKeys: Set[KeyId]): Endpoints = {
    endpoints.find(_.value == endpoint.value) match {
      case Some(ep) =>
        val updatedAuthKeys = endpointsToAuthKeys.getOrElse(ep.id, Set.empty) ++ authKeys
        copy(endpointsToAuthKeys = endpointsToAuthKeys ++ Map(ep.id -> updatedAuthKeys))
      case None    =>
        val otherEndpoints = endpoints.filter(_.id != endpoint.id)
        val authKeyMapping = Map(endpoint.id -> authKeys)
        copy(endpoints = otherEndpoints :+ endpoint, endpointsToAuthKeys = endpointsToAuthKeys ++ authKeyMapping)
    }
  }

  def remove(id: EndpointId): Endpoints = {
    val otherEndpoints = endpoints.filter(_.id != id)
    val otherEndpointsToAuthKeys = endpointsToAuthKeys - id
    copy(endpoints = otherEndpoints, endpointsToAuthKeys = otherEndpointsToAuthKeys)
  }
}

trait EndpointType {
  /**
   * type of the endpoint (routing service endpoint, push endpoint, web-socket endpoint etc)
   * @return
   */
  def `type`: Int
  def isOfType(typ: Int): Boolean = typ == `type`
  def packagingContext: Option[PackagingContext]
}

object PackagingContext {
  def apply(packVersion: String): PackagingContext =
    PackagingContext(MsgPackVersion.fromString(packVersion))
}
case class PackagingContext(packVersion: MsgPackVersion)

trait EndpointLike extends EndpointType {

  /**
   * a unique id of the endpoint
   * @return
   */
  def id: EndpointId

  /**
   * endpoint value
   *
   * @return
   */
  def value: String
}

/**
 * it contains information for legacy routing service endpoint (as per connecting 0.5 and 0.6 protocols)
 *
 * @param agencyDID their agency DID
 * @param agentKeyDID their agent key DID
 * @param agentVerKey their agent ver key
 * @param agentKeyDlgProofSignature their agent key delegation proof signature
 */
case class LegacyRoutingServiceEndpoint(agencyDID: DID,
                                        agentKeyDID: DID,
                                        agentVerKey: VerKey,
                                        agentKeyDlgProofSignature: String)
  extends RoutingServiceEndpointLike {
  def value = "their-route"
}

/**
 * it contains information for standard routing service endpoint (as per connections 1.0 protocol)
 *
 * @param value endpoint http url (for example: http://verity.example.com etc)
 * @param routingKeys an optional ordered list of public keys (RoutingKeys) of
 *                    mediators used by the Receiver in delivering the message
 *                    (see aries RFC for more detail: https://github.com/hyperledger/aries-rfcs/blob/82365b991743e92a34b1b6460f90b5016e22909a/concepts/0094-cross-domain-messaging/README.md)
 */
case class RoutingServiceEndpoint(value: URL,
                                  routingKeys: Vector[VerKey]=Vector.empty)
  extends RoutingServiceEndpointLike


/**
 *
 * @param id a unique endpoint identifier
 * @param value push notification token (provided by corresponding push notification service provider)
 */
case class PushEndpoint(id: EndpointId,
                        value: String) extends EndpointLike with PushEndpointType {
  def packagingContext: Option[PackagingContext] = None
}


/**
 *
 * @param id a unique endpoint identifier
 * @param value http endpoint url
 * @param packagingContext optional packaging context (what pack version to use etc)
 */
case class HttpEndpoint(id: EndpointId,
                        value: String,
                        packagingContext: Option[PackagingContext] = None) extends EndpointLike with HttpEndpointType

/**
 *
 * @param id a unique endpoint identifier
 * @param value http endpoint url (where a message will be sent and that http will in turn send a push notification)
 */
case class ForwardPushEndpoint(id: EndpointId,
                               value: String,
                               packagingContext: Option[PackagingContext] = None) extends EndpointLike with FwdPushEndpointType

case class SponsorPushEndpoint(id: EndpointId,
                               value: String,
                               packagingContext: Option[PackagingContext] = None) extends EndpointLike with SponsorPushEndpointType

object EndpointType {
  final val ROUTING_SERVICE_ENDPOINT = 0    //routing service endpoint (it contains more information than just http endpoint)
  final val PUSH = 1                        //push notification service endpoint type
  final val HTTP = 2                        //http endpoint
  final val FWD_PUSH = 3                    //forward push notification endpoint type
  final val SPR_PUSH = 4                    //sponsor push notification endpoint type
}

/**
 * mostly used for "their" routing service endpoint
 */
trait RoutingServiceEndpointLike extends EndpointLike {
  def id = "0"
  def `type`: Int = EndpointType.ROUTING_SERVICE_ENDPOINT
  def packagingContext: Option[PackagingContext] = None
}


/**
 * mostly used for "my" http endpoint (on edge)
 */
trait HttpEndpointType extends EndpointType {
  def `type`: Int = EndpointType.HTTP
}

/**
 * mostly used for "my" push service endpoint (on edge)
 */
trait PushEndpointType extends EndpointType {
  def `type`: Int = EndpointType.PUSH
}

/**
 * mostly used for "my" forward push endpoint (on edge)
 */
trait FwdPushEndpointType extends EndpointType {
  def `type`: Int = EndpointType.FWD_PUSH
}

/**
 * mostly used for "my" sponsor push endpoint (on edge)
 */
trait SponsorPushEndpointType extends EndpointType {
  def `type`: Int = EndpointType.SPR_PUSH
}


