package com.evernym.verity.actor.agent.msgsender

import com.evernym.verity.constants.Constants._
import com.evernym.verity.Exceptions._
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.Status._
import com.evernym.verity.actor.agent.SpanUtil._
import com.evernym.verity.actor.agent.agency.GetAgencyIdentity
import com.evernym.verity.apphealth.AppStateConstants.CONTEXT_GENERAL
import com.evernym.verity.apphealth.{AppStateManager, ErrorEventParam, MildSystemError}
import com.evernym.verity.cache._
import com.evernym.verity.constants.LogKeyConstants._
import com.evernym.verity.http.common.MsgSendingSvc
import com.evernym.verity.ledger.LedgerSvcException
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.protocols.HasGeneralCache
import com.evernym.verity.protocol.protocols.connecting.common.TheirRoutingParam
import com.evernym.verity.actor.wallet.PackedMsg
import com.evernym.verity.{Exceptions, UrlParam}

import scala.concurrent.Future
import scala.util.Left


/**
 * sends agent messages to based on given routing parameter
 */
trait AgentMsgSender
  extends HasGeneralCache
    with HasLogger {

  def msgSendingSvc: MsgSendingSvc
  def handleMsgDeliveryResult(mdr: MsgDeliveryResult): Unit

  private def getAgencyIdentityFut(localAgencyDID: String, gad: GetAgencyIdentity): Future[CacheQueryResponse] = {
    runWithInternalSpan("getAgencyIdentityFut", "AgentMsgSender") {
      val gadp = GetAgencyIdentityCacheParam(localAgencyDID, gad)
      val gadfcParam = GetCachedObjectParam(Set(KeyDetail(gadp, required = true)), AGENCY_DETAIL_CACHE_FETCHER_ID)
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
    AppStateManager << ErrorEventParam(MildSystemError, CONTEXT_GENERAL,
      new RemoteEndpointNotFoundErrorException(Option(errorMsg)), Option(errorMsg))
    LedgerSvcException(errorMsg)
  }

  private def parseUrlParam(ep: String): UrlParam = {
    try {
      UrlParam(ep)
    } catch {
      case e: Exception =>
        val errorMsg = s"error while parsing endpoint: ${Exceptions.getErrorMsg(e)}"
        AppStateManager << ErrorEventParam(MildSystemError, CONTEXT_GENERAL,
          new InvalidValueException(Option(errorMsg)), Option(errorMsg))
        throw e
    }
  }

  private def getRemoteAgencyEndpoint(implicit sm: SendMsgParam): Future[String] = {
    sm.theirRoutingParam.route match {
      case Left(theirAgencyDID) =>
        theirAgencyEndpointFut(sm.localAgencyDID, theirAgencyDID).mapTo[CacheQueryResponse].map { cqr =>
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
    runWithInternalSpan("sendToTheirAgencyEndpoint", "AgentMsgSender") {
      logger.debug("msg about to be sent to their agent", (LOG_KEY_UID, sm.uid), (LOG_KEY_MSG_TYPE, sm.msgType))
      val epFut = getRemoteAgencyEndpoint
      epFut.map { ep =>
        val urlParam = parseUrlParam(ep)
        logger.debug("remote agency detail received for msg to be sent to remote agent", (LOG_KEY_UID, sm.uid), (LOG_KEY_MSG_TYPE, sm.msgType))
        logger.debug("determined the endpoint to be used", (LOG_KEY_UID, sm.uid), (LOG_KEY_MSG_TYPE, sm.msgType), (LOG_KEY_REMOTE_ENDPOINT, urlParam))
        val respFut = msgSendingSvc.sendBinaryMsg(sm.msg)(urlParam)
        respFut.map {
          case Right(pm: PackedMsg) =>
            logger.debug("msg successfully sent to their agent", (LOG_KEY_UID, sm.uid), (LOG_KEY_MSG_TYPE, sm.msgType))
            handleMsgDeliveryResult(MsgDeliveryResult(sm, MSG_DELIVERY_STATUS_SENT.statusCode, responseMsg = Option(pm)))
          case Left(e: HandledErrorException) =>
            handleMsgDeliveryResult(MsgDeliveryResult(sm, MSG_DELIVERY_STATUS_FAILED.statusCode, Option(Exceptions.getErrorMsg(e))))
          case e: Any =>
            handleMsgDeliveryResult(MsgDeliveryResult(sm, MSG_DELIVERY_STATUS_FAILED.statusCode, Option(e.toString)))
        }.recover {
          case e: Exception =>
            handleMsgDeliveryResult(MsgDeliveryResult(sm, MSG_DELIVERY_STATUS_FAILED.statusCode, Option(Exceptions.getErrorMsg(e))))
        }
      }.recover {
        case e: Exception =>
          handleMsgDeliveryResult(MsgDeliveryResult(sm, MSG_DELIVERY_STATUS_FAILED.statusCode, Option(Exceptions.getErrorMsg(e))))
          throw e
      }
    }
  }
}

case class SendMsgParam(uid: MsgId,
                        msgType: String,
                        msg: Array[Byte],
                        localAgencyDID: DID,
                        theirRoutingParam: TheirRoutingParam,
                        isItARetryAttempt: Boolean)

case class MsgDeliveryResult(sm: SendMsgParam,
                             statusCode: String,
                             statusMsg: Option[String]=None,
                             responseMsg: Option[PackedMsg]=None)
