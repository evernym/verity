package com.evernym.verity.cache.fetchers

import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.cache.base.{KeyDetail, KeyMapping}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.CommonConfig._
import com.evernym.verity.constants.Constants._
import com.evernym.verity.ledger.{LedgerSvc, Submitter}
import com.evernym.verity.protocol.engine.DID

import scala.concurrent.Future

class LedgerVerKeyCacheFetcher(val ledgerSvc: LedgerSvc, val appConfig: AppConfig) extends AsyncCacheValueFetcher {

  lazy val id: Int = LEDGER_GET_VER_KEY_CACHE_FETCHER_ID
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
    gvkpFut.map {
      case Right(vk: String) => Map(gvkp.did -> vk)
      case x => throw buildUnexpectedResponse(x)
    }
  }

}

case class GetVerKeyParam(did: DID, submitterDetail: Submitter) {
  override def toString: String = s"DID: $did, SubmitterDetail: $Submitter"
}
