package com.evernym.verity.cache.fetchers

import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.cache.base.{KeyDetail, KeyMapping}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.CommonConfig._
import com.evernym.verity.constants.Constants._
import com.evernym.verity.ledger.{LedgerSvc, Submitter}
import com.evernym.verity.protocol.engine.DID

import scala.concurrent.Future


case class GetVerKeyParam(did: DID, submitterDetail: Submitter) {
  override def toString: String = s"DID: $did, SubmitterDetail: $Submitter"
}

class LedgerVerKeyCacheFetcher(val ledgerSvc: LedgerSvc, appConfig: AppConfig) extends AsyncCacheValueFetcher {

  lazy val id: Int = LEDGER_VER_KEY_CACHE_FETCHER_ID

  //time to live in seconds, afterwards they will be considered as expired and re-fetched from source
  lazy val expiryTimeInSeconds: Option[Int] = Option(appConfig.getConfigIntOption(LEDGER_VER_KEY_CACHE_EXPIRATION_TIME_IN_SECONDS).getOrElse(1800))
  lazy val maxSize: Option[Int] = appConfig.getConfigIntOption(LEDGER_VER_KEY_CACHE_MAX_SIZE)

  override def toKeyDetailMappings(keyDetails: Set[KeyDetail]): Set[KeyMapping] = {
    keyDetails.map { kd =>
      val gvp = kd.key.asInstanceOf[GetVerKeyParam]
      KeyMapping(kd, gvp.did, gvp.did)
    }
  }

  override def getByKeyDetail(kd: KeyDetail): Future[Map[String, Any]] = {
    val gvkp = kd.key.asInstanceOf[GetVerKeyParam]
    val gvkpFut = ledgerSvc.getNymDataFut(gvkp.submitterDetail, gvkp.did, ledgerSvc.VER_KEY)
    gvkpFut.map {
      case Right(vk: String) => Map(gvkp.did -> vk)
      case x => throw buildUnexpectedResponse(x)
    }
  }

}
