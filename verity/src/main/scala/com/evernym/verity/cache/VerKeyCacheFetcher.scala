package com.evernym.verity.cache

import com.evernym.verity.constants.Constants._
import com.evernym.verity.config.CommonConfig._
import com.evernym.verity.config.AppConfig
import com.evernym.verity.ledger.{LedgerSvc, Submitter}
import com.evernym.verity.protocol.engine.DID

import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import scala.concurrent.Future


case class GetVerKeyParam(did: DID, submitterDetail: Submitter) {
  override def toString: String = s"DID: $did, SubmitterDetail: $Submitter"
}

class VerKeyCacheFetcher(val ledgerSvc: LedgerSvc, appConfig: AppConfig) extends AsyncCacheValueFetcher {

  lazy val id: Int = VER_KEY_CACHE_FETCHER_ID

  //time to live in seconds, afterwards they will be considered as expired and re-fetched from source
  lazy val ttls: Option[Int] = Option(appConfig.getConfigIntOption(VER_KEY_CACHE_EXPIRATION_TIME_IN_SECONDS).getOrElse(1800))

  override def getKeyDetailMapping(kds: Set[KeyDetail]): Set[KeyMapping] = {
    kds.map { kd =>
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
