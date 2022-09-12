package com.evernym.verity.cache.fetchers

import com.evernym.verity.cache.LEDGER_GET_ENDPOINT_CACHE_FETCHER
import com.evernym.verity.cache.base.{CacheRequest, FetcherParam, ReqParam, RespParam}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.ConfigConstants._
import com.evernym.verity.ledger.{AttribResult, LegacyLedgerSvc, Submitter}
import com.evernym.verity.did.DidStr
import com.evernym.verity.util2.Exceptions.BadRequestErrorException
import com.evernym.verity.util2.Status.DATA_NOT_FOUND
import com.evernym.verity.vdr.service.VDRAdapterUtil

import scala.concurrent.{ExecutionContext, Future}

class EndpointCacheFetcher (val legacyLedgerSvc: LegacyLedgerSvc,
                            val appConfig: AppConfig,
                            executionContext: ExecutionContext)
  extends AsyncCacheValueFetcher {
  override def futureExecutionContext: ExecutionContext = executionContext
  private implicit val executionContextImpl: ExecutionContext = executionContext

  lazy val fetcherParam: FetcherParam = LEDGER_GET_ENDPOINT_CACHE_FETCHER
  lazy val cacheConfigPath: Option[String] = Option(LEDGER_GET_ENDPOINT_CACHE)

  //time to live in seconds, afterwards they will be considered as expired and re-fetched from source
  override lazy val defaultExpiryTimeInSeconds: Option[Int] = Option(1800)

  override def toCacheRequests(rp: ReqParam): Set[CacheRequest] = {
    val gvp = rp.cmdAs[GetEndpointParam]
    Set(CacheRequest(rp, gvp.did, gvp.did))
  }

  override def getByRequest(cr: CacheRequest): Future[Option[RespParam]] = {
    val gep = cr.reqParam.cmdAs[GetEndpointParam]
    //TODO: at present vdr apis doesn't support getting endpoints (either via did doc or otherwise)
    // in future when vdr api supports it, we should replace below call accordingly
    val gepFut = legacyLedgerSvc.getAttrib(gep.submitterDetail, gep.did, VDRAdapterUtil.URL)
    gepFut.map {
      case ar: AttribResult if ar.value.isDefined =>
        Option(RespParam(ar.value.map(_.toString).orNull))
      case ar: AttribResult if ar.value.isEmpty =>
        throw new BadRequestErrorException(DATA_NOT_FOUND.statusCode, Option("endpoint not found for DID: " + gep.did))
      case x =>
        throw buildUnexpectedResponse(x)
    }
  }
}

case class GetEndpointParam(did: DidStr, submitterDetail: Submitter) {
  override def toString: String = s"DID: $did, SubmitterDetail: $Submitter"
}
