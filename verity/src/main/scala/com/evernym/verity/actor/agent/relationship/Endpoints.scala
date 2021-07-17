package com.evernym.verity.actor.agent.relationship

import com.evernym.verity.actor.ComMethodAuthentication
import com.evernym.verity.actor.agent._

import scala.language.implicitConversions

trait EndpointsLike {
  this: Endpoints =>

  /**
   * collection of different endpoints
   * @return endpoints
   */
  def endpoints: Seq[EndpointADT]

  validate()

  def validate() {

    if (endpoints.map(_.value).toSet.size != endpoints.size) {
      throw new RuntimeException("endpoint with same 'value' not allowed")
    }

    if (endpoints.map(_.id).toSet.size != endpoints.size) {
      throw new RuntimeException("endpoints with same 'id' not allowed")
    }
  }

  def filterByKeyIds(keyIds: KeyId*): Seq[EndpointADT] = {
    val endpointIds = endpoints.filter(_.authKeyIds.intersect(keyIds).nonEmpty).map(_.id)
    endpoints.filter(ep => endpointIds.contains(ep.id))
  }

  def filterByTypes(types: Int*): Seq[EndpointADT] =
    endpoints.filter(ep => types.toVector.contains(ep.`type`))

  def filterByValues(values: String*): Seq[EndpointADT] =
    endpoints.filter(ep => values.toVector.contains(ep.value))

  def findById(id: EndpointId): Option[EndpointLike] = endpoints.find(_.id == id)

  /**
   * adds/updates given endpoint
   * @param newEndpoint
   * @return
   */
  def upsert(newEndpoint: EndpointADT): Endpoints = {
    endpoints.find(_.value == newEndpoint.value) match {
      case Some(oldEndpoint) =>
        val updatedEndpoint = oldEndpoint.updateAuthKeyIds((oldEndpoint.authKeyIds ++ newEndpoint.authKeyIds).distinct)
        val otherEndpoints = endpoints.filter(_.value != newEndpoint.value)
        copy(endpoints = otherEndpoints :+ updatedEndpoint)
      case None =>
        val otherEndpoints = endpoints.filter(_.id != newEndpoint.id)
        copy(endpoints = otherEndpoints :+ newEndpoint)
    }
  }

  /**
   * adds/updates given endpoint
   * @param endpoint
   * @return
   */
  def upsert(endpoint: EndpointADTUntyped): Endpoints = {
    upsert(EndpointADT.apply(endpoint))
  }

  def remove(id: EndpointId): Endpoints = {
    val otherEndpoints = endpoints.filter(_.id != id)
    copy(endpoints = otherEndpoints)
  }

  def authKeyIdsForEndpoint(id: EndpointId): Seq[KeyId] = endpoints.filter(_.id == id).flatMap(_.authKeyIds)

}

trait EndpointsCompanion {

  def empty: Endpoints = Endpoints(Vector.empty)

  implicit def EndpointADTUntyped2ADT(endpoint: EndpointADTUntyped): EndpointADT = EndpointADT(endpoint)
  implicit def SeqEndpointADTUntyped2ADT(endpoints: Seq[EndpointADTUntyped]): Seq[EndpointADT] = endpoints.map(EndpointADT.apply)

  /**
   *
   * @param endpoint endpoint to be added
   * @return
   */
  def init(endpoint: EndpointADTUntyped): Endpoints =
    Endpoints(Vector(endpoint))

  def init(endpoints: Seq[EndpointADTUntyped]): Endpoints =
    Endpoints(endpoints)
}

trait EndpointType {
  /**
   * type of the endpoint (routing service endpoint, push endpoint, web-socket endpoint etc)
   * @return
   */
  def `type`: Int
  def isOfType(typ: Int): Boolean = typ == `type`
  def packagingContext: Option[PackagingContext]
  def authKeyIds: Seq[KeyId]
  def updateAuthKeyIds(newAuthKeyIds: Seq[String]): EndpointADT
}

trait PackagingContextCompanion {
  def apply(packVersion: String): PackagingContext =
    PackagingContext(MsgPackFormat.fromString(packVersion))
}

trait AuthenticationCompanion {
  def apply(cmAuth: ComMethodAuthentication): Authentication =
    Authentication(cmAuth.`type`, cmAuth.version, cmAuth.data)
}

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
  lazy val endpoint: EndpointLike = this.endpointADTX.asInstanceOf[EndpointLike]
  def id: EndpointId = endpoint.id
  def value: String = endpoint.value
  def `type`: Int = endpoint.`type`
  def packagingContext: Option[PackagingContext] = endpoint.packagingContext
  def authKeyIds: Seq[KeyId] = endpoint.authKeyIds

  def updateAuthKeyIds(newAuthKeyIds: Seq[String]): EndpointADT = endpoint.updateAuthKeyIds(newAuthKeyIds)
}

object EndpointType {
  final val ROUTING_SERVICE_ENDPOINT = 0    //routing service endpoint (it contains more information than just http endpoint)
  final val PUSH = 1                        //push notification service endpoint type
  final val HTTP = 2                        //http endpoint
  final val FWD_PUSH = 3                    //forward push notification endpoint type
  final val SPR_PUSH = 4                    //sponsor push notification endpoint type
}


trait RoutingServiceEndpointBase extends EndpointLike {
  def id = "0"
  def `type`: Int = EndpointType.ROUTING_SERVICE_ENDPOINT
  def packagingContext: Option[PackagingContext] = None
}

/**
 * mostly used for "their" routing service endpoint
 */
trait RoutingServiceEndpointLike
  extends RoutingServiceEndpointBase { this: RoutingServiceEndpoint =>
  def updateAuthKeyIds(newAuthKeyIds: Seq[String]): EndpointADT =
    EndpointADT(copy(authKeyIds = newAuthKeyIds))
}

trait LegacyRoutingServiceEndpointLike
  extends RoutingServiceEndpointBase { this: LegacyRoutingServiceEndpoint =>
  def value = "their-route"
  def updateAuthKeyIds(newAuthKeyIds: Seq[String]): EndpointADT =
    EndpointADT(copy(authKeyIds = newAuthKeyIds))
}

trait HttpEndpointLike
  extends EndpointLike
    with HttpEndpointType { this: HttpEndpoint =>
  def updateAuthKeyIds(newAuthKeyIds: Seq[String]): EndpointADT =
    EndpointADT(copy(authKeyIds = newAuthKeyIds))
}

trait ForwardPushEndpointLike
  extends EndpointLike
    with FwdPushEndpointType { this: ForwardPushEndpoint =>
  def updateAuthKeyIds(newAuthKeyIds: Seq[String]): EndpointADT =
    EndpointADT(copy(authKeyIds = newAuthKeyIds))
}

trait SponsorPushEndpointLike
  extends EndpointLike
    with SponsorPushEndpointType
    with EnforceNoAuthKeyIds { this: SponsorPushEndpoint =>
  def updateAuthKeyIds(newAuthKeyIds: Seq[String]): EndpointADT = EndpointADT(this)
}

trait PushEndpointLike
  extends EndpointLike
    with PushEndpointType
    with EnforceNoAuthKeyIds { this: PushEndpoint =>
  def updateAuthKeyIds(newAuthKeyIds: Seq[String]): EndpointADT = EndpointADT(this)
}

/**
 * mostly used for "my" http endpoint (on edge)
 */
trait HttpEndpointType extends EndpointType {
  def `type`: Int = EndpointType.HTTP
  def authentication: Option[Authentication]
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

trait EnforceNoAuthKeyIds { this: EndpointType =>
  require(authKeyIds.isEmpty, s"${this.getClass.getSimpleName} shouldn't have non empty 'authKeyIds'")
}
