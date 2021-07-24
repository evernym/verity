package com.evernym.verity.cache.fetchers

import com.evernym.verity.util2.Exceptions.BadRequestErrorException
import com.evernym.verity.util2.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.util2.Status._
import com.evernym.verity.cache.LEDGER_GET_ENDPOINT_CACHE_FETCHER
import com.evernym.verity.cache.base.{FetcherParam, KeyDetail, KeyMapping}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.ConfigConstants._
import com.evernym.verity.ledger.{AttribResult, LedgerSvc, Submitter}
import com.evernym.verity.protocol.engine.DID

import scala.concurrent.Future

class EndpointCacheFetcher (val ledgerSvc: LedgerSvc, val appConfig: AppConfig)
  extends AsyncCacheValueFetcher {

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
    val gepFut = ledgerSvc.getAttribFut(gep.submitterDetail, gep.did, ledgerSvc.URL)
    gepFut.map {
      case ar: AttribResult if ar.value.isDefined => Map(gep.did -> ar.value.map(_.toString).orNull)
      case ar: AttribResult if ar.value.isEmpty =>
        throw new BadRequestErrorException(DATA_NOT_FOUND.statusCode, Option("endpoint not found for DID: " + gep.did))
      case x => throw buildUnexpectedResponse(x)
    }
  }

}

case class GetEndpointParam(did: DID, submitterDetail: Submitter) {
  override def toString: String = s"DID: $did, SubmitterDetail: $Submitter"
}
