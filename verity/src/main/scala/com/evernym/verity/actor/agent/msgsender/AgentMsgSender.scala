package com.evernym.verity.actor.agent.msgsender

import com.evernym.verity.actor.agent.HasGeneralCache
import com.evernym.verity.util2.Exceptions._
import com.evernym.verity.util2.HasExecutionContextProvider
import com.evernym.verity.util2.Status._
import com.evernym.verity.actor.agent.agency.GetAgencyIdentity
import com.evernym.verity.actor.appStateManager.AppStateEvent
import com.evernym.verity.constants.LogKeyConstants._
import com.evernym.verity.ledger.LedgerSvcException
import com.evernym.verity.protocol.protocols.connecting.common.TheirRoutingParam
import com.evernym.verity.actor.wallet.PackedMsg
import com.evernym.verity.cache.AGENCY_IDENTITY_CACHE_FETCHER
import com.evernym.verity.cache.base.{QueryResult, GetCachedObjectParam, ReqParam}
import com.evernym.verity.cache.fetchers.GetAgencyIdentityCacheParam
import com.evernym.verity.did.DidStr
import com.evernym.verity.did.didcomm.v1.messages.MsgId
import com.evernym.verity.observability.logs.HasLogger
import com.evernym.verity.observability.metrics.{InternalSpan, MetricsWriter}
import com.evernym.verity.transports.MsgSendingSvc
import com.evernym.verity.util2.UrlParam
import com.evernym.verity.util2.Exceptions

import scala.concurrent.{ExecutionContext, Future}


/**
 * sends agent messages to based on given routing parameter
 */
trait AgentMsgSender
  extends HasGeneralCache
    with HasLogger
    with HasExecutionContextProvider {

  private implicit val executionContext: ExecutionContext = futureExecutionContext

  def publishAppStateEvent (event: AppStateEvent): Unit
  def msgSendingSvc: MsgSendingSvc
  def handleMsgDeliveryResult(mdr: MsgDeliveryResult): Unit

  def getAgencyIdentityFut(localAgencyDID: String,
                           gad: GetAgencyIdentity,
                           mw: MetricsWriter): Future[QueryResult] = {
    mw.runWithSpan("getAgencyIdentityFut", "AgentMsgSender", InternalSpan) {
      val gadp = GetAgencyIdentityCacheParam(localAgencyDID, gad)
      val gadfcParam = GetCachedObjectParam(ReqParam(gadp, required = true), AGENCY_IDENTITY_CACHE_FETCHER)
      generalCache.getByParamAsync(gadfcParam)
    }
  }

  private def theirAgencyEndpointFut(localAgencyDID:DidStr,
                                     theirAgencyDID: DidStr,
                                     mw: MetricsWriter): Future[QueryResult] = {
    val gad = GetAgencyIdentity(theirAgencyDID)
    getAgencyIdentityFut(localAgencyDID, gad, mw)
  }

  private def handleRemoteAgencyEndpointNotFound(theirAgencyDID: DidStr, errorMsg: String): Exception = {
    LedgerSvcException(s"error while getting endpoint from ledger (target DID: $theirAgencyDID): $errorMsg")
  }

  private def getRemoteAgencyEndpoint(implicit sm: SendMsgParam, mw: MetricsWriter): Future[String] = {
    sm.theirRoutingParam.route match {
      case Left(theirAgencyDID) =>
        theirAgencyEndpointFut(sm.localAgencyDID, theirAgencyDID, mw).map { cqr =>
          val theirAgencyInfo = cqr.getAgencyInfoReq(theirAgencyDID)
          theirAgencyInfo.endpointOpt.getOrElse(
            throw handleRemoteAgencyEndpointNotFound(theirAgencyDID, "endpoint found to be empty (via cache)")
          )
        }.recover {
          case e: Exception => throw handleRemoteAgencyEndpointNotFound(theirAgencyDID, e.getMessage)
        }
      case Right(endpoint) => Future.successful(endpoint)
    }
  }

  def sendToTheirAgencyEndpoint(implicit sm: SendMsgParam, mw: MetricsWriter): Future[Any] = {
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
        logger.error(s"error while sending message to their agency endpoint '${sm.theirRoutingParam.route}': " + Exceptions.getStackTraceAsString(e))
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
                        localAgencyDID: DidStr,
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
