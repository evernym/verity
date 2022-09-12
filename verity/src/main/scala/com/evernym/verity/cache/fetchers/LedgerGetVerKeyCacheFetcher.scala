package com.evernym.verity.cache.fetchers

import com.evernym.verity.util2.Status.StatusDetailException
import com.evernym.verity.cache.LEDGER_GET_VER_KEY_CACHE_FETCHER
import com.evernym.verity.cache.base.{CacheRequest, FetcherParam, ReqParam, RespParam}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.ConfigConstants._
import com.evernym.verity.ledger.Submitter
import com.evernym.verity.did.DidStr
import com.evernym.verity.vdr.{LedgerPrefix, VDRAdapter, VDRUtil}

import scala.concurrent.{ExecutionContext, Future}

class LedgerVerKeyCacheFetcher(val vdr: VDRAdapter,
                               val appConfig: AppConfig,
                               executionContext: ExecutionContext)
  extends AsyncCacheValueFetcher {
  override implicit def futureExecutionContext: ExecutionContext = executionContext

  lazy val isVdrMultiLedgerSupportEnabled: Boolean = appConfig.getBooleanReq(VDR_MULTI_LEDGER_SUPPORT_ENABLED)
  lazy val vdrUnqualifiedLedgerPrefix: LedgerPrefix = appConfig.getStringReq(VDR_UNQUALIFIED_LEDGER_PREFIX)
  lazy val vdrLedgerPrefixMappings: Map[LedgerPrefix, LedgerPrefix] = appConfig.getMap(VDR_LEDGER_PREFIX_MAPPINGS)

  lazy val fetcherParam: FetcherParam = LEDGER_GET_VER_KEY_CACHE_FETCHER
  lazy val cacheConfigPath: Option[String] = Option(ROUTING_DETAIL_CACHE)

  //time to live in seconds, afterwards they will be considered as expired and re-fetched from source
  override lazy val defaultExpiryTimeInSeconds: Option[Int] = Option(1800)

  override def toCacheRequests(rp: ReqParam): Set[CacheRequest] = {
    val gvp = rp.cmdAs[GetVerKeyParam]
    Set(CacheRequest(rp, gvp.did, gvp.did))
  }

  override def getByRequest(cr: CacheRequest): Future[Option[RespParam]] = {
    val gvp = cr.reqParam.cmdAs[GetVerKeyParam]
    val didDocFut = vdr.resolveDID(VDRUtil.toFqDID(gvp.did, isVdrMultiLedgerSupportEnabled, vdrUnqualifiedLedgerPrefix, vdrLedgerPrefixMappings))
    didDocFut
      .map { dd => Option(RespParam(dd.verKey))}
      .recover {
        case StatusDetailException(sd) => throw buildUnexpectedResponse(sd)
      }
  }
}

case class GetVerKeyParam(did: DidStr, submitterDetail: Submitter) {
  override def toString: String = s"DID: $did, SubmitterDetail: $Submitter"
}
