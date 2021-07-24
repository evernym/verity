package com.evernym.verity.cache.fetchers

import com.evernym.verity.util2.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.util2.Status.StatusDetailException
import com.evernym.verity.cache.LEDGER_GET_VER_KEY_CACHE_FETCHER
import com.evernym.verity.cache.base.{FetcherParam, KeyDetail, KeyMapping}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.ConfigConstants._
import com.evernym.verity.ledger.{LedgerSvc, Submitter}
import com.evernym.verity.protocol.engine.DID

import scala.concurrent.Future

class LedgerVerKeyCacheFetcher(val ledgerSvc: LedgerSvc, val appConfig: AppConfig) extends AsyncCacheValueFetcher {

  lazy val fetcherParam: FetcherParam = LEDGER_GET_VER_KEY_CACHE_FETCHER
  lazy val cacheConfigPath: Option[String] = Option(ROUTING_DETAIL_CACHE)

  //time to live in seconds, afterwards they will be considered as expired and re-fetched from source
  override lazy val defaultExpiryTimeInSeconds: Option[Int] = Option(1800)

  override def toKeyDetailMappings(keyDetails: Set[KeyDetail]): Set[KeyMapping] = {
    keyDetails.map { kd =>
      val gvp = kd.keyAs[GetVerKeyParam]
      KeyMapping(kd, gvp.did, gvp.did)
    }
  }

  override def getByKeyDetail(kd: KeyDetail): Future[Map[String, AnyRef]] = {
    val gvkp = kd.keyAs[GetVerKeyParam]
    val gvkpFut = ledgerSvc.getNymDataFut(gvkp.submitterDetail, gvkp.did, ledgerSvc.VER_KEY)
    gvkpFut.map { vk =>
      Map(gvkp.did -> vk)
    }.recover {
      case StatusDetailException(sd) => throw buildUnexpectedResponse(sd)
    }
  }

}

case class GetVerKeyParam(did: DID, submitterDetail: Submitter) {
  override def toString: String = s"DID: $did, SubmitterDetail: $Submitter"
}
