package com.evernym.verity.protocol.protocols.connecting.common

import akka.actor.Actor.Receive
import com.evernym.verity.Exceptions.BadRequestErrorException
import com.evernym.verity.ServiceEndpoint
import com.evernym.verity.Status.{CONN_STATUS_NOT_CONNECTED, MSG_STATUS_ACCEPTED}
import com.evernym.verity.actor.agent.state.HasConnectionStatus
import com.evernym.verity.actor.{ConnectionCompleted, ConnectionStatusUpdated}
import com.evernym.verity.protocol.engine.{DID, VerKey}


/**
 * A trait meant to be mixed into the state object of an agent
 *
 * contains information about the "connection status" (requested, accepted etc)
 * and their (other participant's) DID Doc
 */
trait HasPairwiseConnection extends HasConnectionStatus {

  private var _theirDIDDoc: Option[LegacyDIDDoc] = None
  def theirDIDDoc: Option[LegacyDIDDoc] = _theirDIDDoc
  def setTheirDIDDoc(ldd: LegacyDIDDoc): Unit = _theirDIDDoc = Option(ldd)
  def theirDIDDocReq: LegacyDIDDoc = _theirDIDDoc.getOrElse(
    throw new BadRequestErrorException(CONN_STATUS_NOT_CONNECTED.statusCode))

  def theirPairwiseDID: Option[DID] = _theirDIDDoc.flatMap(_.DID)
  def theirPairwiseDIDReq: DID = theirPairwiseDID.getOrElse(
    throw new BadRequestErrorException(CONN_STATUS_NOT_CONNECTED.statusCode))

  def isTheirAgentVerKey(verKey: VerKey): Boolean = {
    _theirDIDDoc.exists(_.isTheirAgentVerKey(verKey))
  }

  /**
   * that agent (belonging to other part of the connection)
   * @return
   */
  def theirAgentKeyDID: Option[DID] =
    (_theirDIDDoc.flatMap(_.legacyRoutingDetail), _theirDIDDoc.flatMap(_.routingDetail)) match {
      case (Some(v1), None) => Option(v1.agentKeyDID)
      case _                => None
    }

  def theirRoutingParam: TheirRoutingParam = theirDIDDocReq.theirRoutingParam

  /**
   * this is just used as an identifier of their agent (some unique target which we record in msg delivery state)
   * @return
   */
  def theirRoutingTarget: String = theirRoutingParam.routingTarget

}

trait HasPairwiseConnectionState {

  type StateType <: HasPairwiseConnection
  def state: StateType

  def pairwiseConnReceiver: Receive = {
    case cc: ConnectionStatusUpdated =>
      val legacyRoutingDetail = cc.theirDidDocDetail.map( tdd =>
        ( Option(tdd.pairwiseDID),
          LegacyRoutingDetail(tdd.agencyDID, tdd.agentKeyDID, tdd.agentVerKey, tdd.agentKeyDlgProofSignature)
        )
      )
      val routingDetail = cc.theirProvisionalDidDocDetail.map( ptdd =>
        ( Option(ptdd.did),
          RoutingDetail(ptdd.verKey, ptdd.endpoint, ptdd.routingKeys.toVector)
        )
      )
      val theirDID = legacyRoutingDetail.flatMap(_._1).orElse(routingDetail.flatMap(_._1))
      state.setTheirDIDDoc(
        LegacyDIDDoc(
          DID = theirDID,
          legacyRoutingDetail = legacyRoutingDetail.map(_._2),
          routingDetail = routingDetail.map(_._2)
        )
      )
      state.setConnectionStatus(ConnectionStatus(cc.reqReceived, cc.answerStatusCode))

    case cc: ConnectionCompleted =>
      state.setTheirDIDDoc(
        LegacyDIDDoc(
          Option(cc.theirEdgeDID),
          Option(
            LegacyRoutingDetail(
              cc.theirAgencyDID,
              cc.theirAgentDID,
              cc.theirAgentDIDVerKey,
              cc.theirAgentKeyDlgProofSignature))
      ))
      state.setConnectionStatus(ConnectionStatus(reqReceived=true, MSG_STATUS_ACCEPTED.statusCode))
  }
}

/**
 *
 * @param reqReceived true if connection request is received, else false
 * @param answerStatusCode user's response to the connection request (accepted/rejected etc)
 */
case class ConnectionStatus(reqReceived: Boolean, answerStatusCode: String)

/**
 * Calling this as a legacy DIDDoc because it is not per standard DIDDoc
 * and sooner or later we need to converge to the standard one
 * (at a time either 'legacyRoutingDetail' will be defined or 'routingDetail')
 * @param DID the DID of the DID DOC
 * @param legacyRoutingDetail legacy (based on old connecting 0.5 and 0.6 protocols) routing details
 * @param routingDetail new routing details (based on new aries connections 1.0 protocol)
 */
case class LegacyDIDDoc(DID: Option[DID],
                        legacyRoutingDetail: Option[LegacyRoutingDetail]=None,
                        routingDetail: Option[RoutingDetail]=None) {

  def isTheirAgentVerKey(verKey: VerKey): Boolean = {
    if (legacyRoutingDetail.isDefined) legacyRoutingDetail.exists(_.agentVerKey == verKey)
    else if (routingDetail.isDefined) routingDetail.exists(_.verKey == verKey)
    else false
  }

  def theirRoutingParam: TheirRoutingParam = {
    val route = (legacyRoutingDetail, routingDetail) match {
      case (Some(lrd), None)      => Left(lrd.agencyDID)
      case (None,      Some(rd))  => Right(rd.endpoint)
      case x                      => throw new RuntimeException("unsupported routing detail: " + x)
    }
    TheirRoutingParam(route)
  }
}

sealed trait RoutingDetailProvider

case class LegacyRoutingDetail(
                                agencyDID: DID,
                                agentKeyDID: DID,
                                agentVerKey: VerKey,
                                agentKeyDlgProofSignature: String)
  extends RoutingDetailProvider

case class RoutingDetail(verKey: VerKey,
                         endpoint: ServiceEndpoint,
                         routingKeys: Vector[VerKey])
  extends RoutingDetailProvider

/**
 * in legacy routing detail, it used to be routing service's (agency's) DID
 * in new routing details, it used to be the endpoint itself
 * @param route either DID or service endpoint itself
 */
case class TheirRoutingParam(route: Either[DID, ServiceEndpoint]) {

  def routingTarget: String = route match {
    case Left(did)  => did
    case Right(ep)  => ep
  }
}


case class AgentKeyDlgProof(agentDID: DID, agentDelegatedKey: String, signature: String) {
  def buildChallenge: String = agentDID + agentDelegatedKey

  def toAbbreviated: AgentKeyDlgProofAbbreviated = AgentKeyDlgProofAbbreviated(agentDID, agentDelegatedKey, signature)
}

case class AgentKeyDlgProofAbbreviated(d: DID, k: String, s: String) {
  def buildChallenge: String = d + k
}

case class RemoteAgencyIdentity(did: DID, verKey: VerKey, endpoint: String)
