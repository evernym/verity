package com.evernym.verity.actor.agent.relationship

import com.evernym.verity.actor.agent._

import scala.language.implicitConversions

trait EndpointsLike {
  this: Endpoints =>
  /**
   * collection of different endpoints
   * @return endpoints
   */
  def endpoints: Seq[EndpointADT]

  /**
   * mapping between endpoint id and auth key ids
   * @return endpointsToAuthKeys
   */
  def endpointsToAuthKeys: Map[EndpointId, KeyIds]

  validate()

  def validate() {

    endpointsToAuthKeys.keySet.find(eid => !endpoints.map(_.id).contains(eid)).map { eid =>
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

  def filterByKeyIds(keyIds: KeyId*): Seq[EndpointADT] = {
    val endpointIds = endpointsToAuthKeys.filter(_._2.keyId.intersect(keyIds.toSet).nonEmpty).keySet
    endpoints.filter(ep => endpointIds.contains(ep.id))
  }

  def filterByTypes(types: Int*): Seq[EndpointADT] =
    endpoints.filter(ep => types.toVector.contains(ep.`type`))

  def filterByValues(values: String*): Seq[EndpointADT] =
    endpoints.filter(ep => values.toVector.contains(ep.value))

  def findById(id: EndpointId): Option[EndpointLike] = endpoints.find(_.id == id)

  def addOrUpdate(endpoint: EndpointADT, authKeys: Set[KeyId]): Endpoints = {
    endpoints.find(_.value == endpoint.value) match {
      case Some(ep) =>
        val updatedAuthKeys = endpointsToAuthKeys.getOrElse(ep.id, KeyIds()).addAllKeyId(authKeys)
        val e2ak = endpointsToAuthKeys ++ Map(ep.id -> updatedAuthKeys)
        this.
        copy(endpointsToAuthKeys = e2ak)
      case None =>
        val otherEndpoints = endpoints.filter(_.id != endpoint.id)
        val authKeyMapping = Map(endpoint.id -> KeyIds(authKeys))
        val newE2ak = endpointsToAuthKeys ++ authKeyMapping
        copy(endpoints = otherEndpoints :+ endpoint, endpointsToAuthKeys = newE2ak)
    }
  }

  def remove(id: EndpointId): Endpoints = {
    val otherEndpoints = endpoints.filter(_.id != id)
    val otherEndpointsToAuthKeys = endpointsToAuthKeys - id
    copy(endpoints = otherEndpoints, endpointsToAuthKeys = otherEndpointsToAuthKeys)
  }
}

trait EndpointsCompanion {

  def empty: Endpoints = Endpoints(Vector.empty, Map.empty)

  implicit def EndpointADTUntyped2ADT(endpoint: EndpointADTUntyped): EndpointADT = EndpointADT(endpoint)
  implicit def SeqEndpointADTUntyped2ADT(endpoints: Seq[EndpointADTUntyped]): Seq[EndpointADT] = endpoints.map(EndpointADT.apply)

  /**
   *
   * @param endpoint endpoint to be added
   * @param authKey auth key belonging to provided endpoint
   * @return
   */
  def init(endpoint: EndpointADTUntyped, authKey: KeyId): Endpoints =
    init(Vector(endpoint), Set(authKey))

  /**
   *
   * @param endpoint endpoint to be added
   * @param authKeys auth keys belonging to provided endpoint
   * @return
   */
  def init(endpoint: EndpointADTUntyped, authKeys: Set[KeyId]): Endpoints =
    init(Vector(endpoint), authKeys)

  /**
   *
   * @param endpoints endpoints to be added
   * @param authKeys  auth keys belonging to all given endpoints
   * @return
   */
  def init(endpoints: Seq[EndpointADTUntyped], authKeys: Set[KeyId] = Set.empty): Endpoints = {
    val ep2 = endpoints.map(EndpointADT.apply)
    lazy val keyIds = KeyIds(authKeys)
    val endpointsToAuthKeys = ep2.map(ep => ep.id -> keyIds).toMap
    Endpoints(ep2, endpointsToAuthKeys)
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

trait PackagingContextCompanion {
  def apply(packVersion: String): PackagingContext =
    PackagingContext(MsgPackVersion.fromString(packVersion))
}
//object PackagingContext extends PackagingContextCompanion
//case class PackagingContext(packVersion: MsgPackVersion)

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

trait EndpointLikePassThrough extends EndpointLike {
  this: EndpointADT =>
  lazy val endpoint = this.endpointADTX.asInstanceOf[EndpointLike]
  def id = endpoint.id
  def value = endpoint.value
  def `type`= endpoint.`type`
  def packagingContext = endpoint.packagingContext
}

///**
// * it contains information for legacy routing service endpoint (as per connecting 0.5 and 0.6 protocols)
// *
// * @param agencyDID their agency DID
// * @param agentKeyDID their agent key DID
// * @param agentVerKey their agent ver key
// * @param agentKeyDlgProofSignature their agent key delegation proof signature
// */
//case class LegacyRoutingServiceEndpoint(agencyDID: DID,
//                                        agentKeyDID: DID,
//                                        agentVerKey: VerKey,
//                                        agentKeyDlgProofSignature: String)
//  extends LegacyRoutingServiceEndpointLike

trait LegacyRoutingServiceEndpointLike extends RoutingServiceEndpointLike {
  def value = "their-route"
}

///**
// * it contains information for standard routing service endpoint (as per connections 1.0 protocol)
// *
// * @param value endpoint http url (for example: http://verity.example.com etc)
// * @param routingKeys an optional ordered list of public keys (RoutingKeys) of
// *                    mediators used by the Receiver in delivering the message
// *                    (see aries RFC for more detail: https://github.com/hyperledger/aries-rfcs/blob/82365b991743e92a34b1b6460f90b5016e22909a/concepts/0094-cross-domain-messaging/README.md)
// */
//case class RoutingServiceEndpoint(value: URL,
//                                  routingKeys: Vector[VerKey]=Vector.empty)
//  extends RoutingServiceEndpointLike


///**
// *
// * @param id a unique endpoint identifier
// * @param value push notification token (provided by corresponding push notification service provider)
// */
//case class PushEndpoint(id: EndpointId,
//                        value: String) extends EndpointLike with PushEndpointType {
//  def packagingContext: Option[PackagingContext] = None
//}


///**
// *
// * @param id a unique endpoint identifier
// * @param value http endpoint url
// * @param packagingContext optional packaging context (what pack version to use etc)
// */
//case class HttpEndpoint(id: EndpointId,
//                        value: String,
//                        packagingContext: Option[PackagingContext] = None) extends EndpointLike with HttpEndpointType

///**
// *
// * @param id a unique endpoint identifier
// * @param value http endpoint url (where a message will be sent and that http will in turn send a push notification)
// */
//case class ForwardPushEndpoint(id: EndpointId,
//                               value: String,
//                               packagingContext: Option[PackagingContext] = None) extends EndpointLike with FwdPushEndpointType
//
//case class SponsorPushEndpoint(id: EndpointId,
//                               value: String,
//                               packagingContext: Option[PackagingContext] = None) extends EndpointLike with SponsorPushEndpointType

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
  def packagingContext: Option[PackagingContext] = None
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


