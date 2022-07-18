package com.evernym.verity.cache.fetchers

import com.evernym.verity.util2.Exceptions.BadRequestErrorException
import com.evernym.verity.util2.Status._
import com.evernym.verity.cache.LEDGER_GET_ENDPOINT_CACHE_FETCHER
import com.evernym.verity.cache.base.{FetcherParam, KeyDetail, KeyMapping}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.ConfigConstants._
import com.evernym.verity.ledger.{AttribResult, LedgerSvc, Submitter}
import com.evernym.verity.did.DidStr
import com.evernym.verity.protocol.engine.asyncapi.ledger.LedgerAccess
import com.evernym.verity.vdr.{DidDoc, VDRAdapter}

import scala.concurrent.{ExecutionContext, Future}

class EndpointCacheFetcher (val vdr: VDRAdapter, val appConfig: AppConfig, executionContext: ExecutionContext)
  extends AsyncCacheValueFetcher {
  override def futureExecutionContext: ExecutionContext = executionContext
  private implicit val executionContextImpl: ExecutionContext = executionContext

  lazy val fetcherParam: FetcherParam = LEDGER_GET_ENDPOINT_CACHE_FETCHER
  lazy val cacheConfigPath: Option[String] = Option(LEDGER_GET_ENDPOINT_CACHE)

  //time to live in seconds, afterwards they will be considered as expired and re-fetched from source
  override lazy val defaultExpiryTimeInSeconds: Option[Int] = Option(1800)

  override def toKeyDetailMappings(keyDetails: Set[KeyDetail]): Set[KeyMapping] = {
    keyDetails.map { kd =>
      val gvp = kd.keyAs[GetEndpointParam]
      KeyMapping(kd, gvp.did, gvp.did)
    }
  }

  override def getByKeyDetail(kd: KeyDetail): Future[Map[String, AnyRef]] = {
    val gep = kd.keyAs[GetEndpointParam]
    val didDocFut = vdr.resolveDID(gep.did)
    didDocFut.map {
      case dd: DidDoc => Map(gep.did -> dd.endpoint.map(_.toString).orNull)
      case x => throw buildUnexpectedResponse(x)
    }
  }
}

case class GetEndpointParam(did: DidStr, submitterDetail: Submitter) {
  override def toString: String = s"DID: $did, SubmitterDetail: $Submitter"
}
