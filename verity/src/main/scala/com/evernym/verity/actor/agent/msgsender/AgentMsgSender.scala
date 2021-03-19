package com.evernym.verity.actor.agent.msgsender

import com.evernym.verity.constants.Constants._
import com.evernym.verity.Exceptions._
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.Status._
import com.evernym.verity.actor.agent.SpanUtil._
import com.evernym.verity.actor.agent.agency.GetAgencyIdentity
import com.evernym.verity.actor.appStateManager.{AppStateEvent, ErrorEvent, MildSystemError}
import com.evernym.verity.actor.appStateManager.AppStateConstants._
import com.evernym.verity.constants.LogKeyConstants._
import com.evernym.verity.http.common.MsgSendingSvc
import com.evernym.verity.ledger.LedgerSvcException
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.protocols.HasGeneralCache
import com.evernym.verity.protocol.protocols.connecting.common.TheirRoutingParam
import com.evernym.verity.actor.wallet.PackedMsg
import com.evernym.verity.cache.base.{CacheQueryResponse, GetCachedObjectParam, KeyDetail}
import com.evernym.verity.cache.fetchers.GetAgencyIdentityCacheParam
import com.evernym.verity.{Exceptions, UrlParam}

import scala.concurrent.Future
import scala.util.Left


/**
 * sends agent messages to based on given routing parameter
 */
trait AgentMsgSender
  extends HasGeneralCache
    with HasLogger {

  def publishAppStateEvent (event: AppStateEvent): Unit
  def msgSendingSvc: MsgSendingSvc
  def handleMsgDeliveryResult(mdr: MsgDeliveryResult): Unit

  private def getAgencyIdentityFut(localAgencyDID: String, gad: GetAgencyIdentity): Future[CacheQueryResponse] = {
    runWithInternalSpan("getAgencyIdentityFut", "AgentMsgSender") {
      val gadp = GetAgencyIdentityCacheParam(localAgencyDID, gad)
      val gadfcParam = GetCachedObjectParam(KeyDetail(gadp, required = true), AGENCY_IDENTITY_CACHE_FETCHER_ID)
      generalCache.getByParamAsync(gadfcParam)
    }
  }

  private def theirAgencyEndpointFut(localAgencyDID:DID, theirAgencyDID: DID): Future[CacheQueryResponse] = {
    val gad = GetAgencyIdentity(theirAgencyDID, getVerKey = false)
    getAgencyIdentityFut(localAgencyDID, gad)
  }

  private def handleRemoteAgencyEndpointNotFound(theirAgencyDID: DID): Exception = {
    val errorMsg =
      "error while getting endpoint from ledger (" +
        "possible-causes: ledger pool not reachable/up/responding etc, " +
        s"target DID: $theirAgencyDID)"
    publishAppStateEvent(ErrorEvent(MildSystemError, CONTEXT_GENERAL,
      new RemoteEndpointNotFoundErrorException(Option(errorMsg)), Option(errorMsg)))
    LedgerSvcException(errorMsg)
  }

  private def getRemoteAgencyEndpoint(implicit sm: SendMsgParam): Future[String] = {
    sm.theirRoutingParam.route match {
      case Left(theirAgencyDID) =>
        theirAgencyEndpointFut(sm.localAgencyDID, theirAgencyDID).map { cqr =>
          cqr.getAgencyInfoReq(theirAgencyDID).endpointOpt.getOrElse(
            throw handleRemoteAgencyEndpointNotFound(theirAgencyDID)
          )
        }.recover {
          case _: Exception =>
            throw handleRemoteAgencyEndpointNotFound(theirAgencyDID)
        }
      case Right(endpoint) => Future.successful(endpoint)
    }
  }

  def sendToTheirAgencyEndpoint(implicit sm: SendMsgParam): Future[Any] = {
    logger.debug("msg about to be sent to their agent", (LOG_KEY_UID, sm.uid), (LOG_KEY_MSG_TYPE, sm.msgType))
    val epFut = getRemoteAgencyEndpoint
    epFut.flatMap { ep =>
      val urlParam = UrlParam(ep)
      logger.debug("remote agency detail received for msg to be sent to remote agent", (LOG_KEY_UID, sm.uid), (LOG_KEY_MSG_TYPE, sm.msgType))
      logger.debug("determined the endpoint to be used", (LOG_KEY_UID, sm.uid), (LOG_KEY_MSG_TYPE, sm.msgType), (LOG_KEY_REMOTE_ENDPOINT, urlParam))
      msgSendingSvc.sendBinaryMsg(sm.msg)(urlParam)
    }.map { r =>
      handleSendMsgResp(sm)(r)
      r
    }.recover {
      case e: Exception =>
        handleMsgDeliveryResult(MsgDeliveryResult.failed(sm, MSG_DELIVERY_STATUS_FAILED, Exceptions.getErrorMsg(e)))
        throw e
    }
  }

  private def handleSendMsgResp(sm: SendMsgParam): PartialFunction[Either[HandledErrorException, PackedMsg], Unit] = {
    case Right(pm: PackedMsg) =>
      logger.debug("msg successfully sent to their agent", (LOG_KEY_UID, sm.uid), (LOG_KEY_MSG_TYPE, sm.msgType))
      handleMsgDeliveryResult(MsgDeliveryResult.success(sm, MSG_DELIVERY_STATUS_SENT, pm))
    case Left(e: HandledErrorException) =>
      handleMsgDeliveryResult(MsgDeliveryResult.failed(sm, MSG_DELIVERY_STATUS_FAILED, Exceptions.getErrorMsg(e)))
    case e: Any =>
      handleMsgDeliveryResult(MsgDeliveryResult.failed(sm, MSG_DELIVERY_STATUS_FAILED, e.toString))
  }
}

case class SendMsgParam(uid: MsgId,
                        msgType: String,
                        msg: Array[Byte],
                        localAgencyDID: DID,
                        theirRoutingParam: TheirRoutingParam,
                        isItARetryAttempt: Boolean)

object MsgDeliveryResult {
  def success(sm: SendMsgParam,
            statusDetail: StatusDetail,
            responseMsg: PackedMsg): MsgDeliveryResult = {
    MsgDeliveryResult(sm, statusDetail.statusCode, None, Option(responseMsg))
  }
  def failed(sm: SendMsgParam,
            statusDetail: StatusDetail,
            statusMsg: String): MsgDeliveryResult = {
    MsgDeliveryResult(sm, statusDetail.statusCode, Option(statusMsg), None)
  }
}
case class MsgDeliveryResult(sm: SendMsgParam,
                             statusCode: String,
                             statusMsg: Option[String]=None,
                             responseMsg: Option[PackedMsg]=None)
