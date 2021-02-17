package com.evernym.verity.cache.fetchers

import com.evernym.verity.cache.base.{KeyDetail, KeyMapping}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.CommonConfig._
import com.evernym.verity.ledger.LedgerSvc
import com.evernym.verity.constants.Constants._
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext

import scala.concurrent.Future

class LedgerGetCredDefCacheFetcher(val ledgerSvc: LedgerSvc, val appConfig: AppConfig)
  extends AsyncCacheValueFetcher {

  override def id: Int = LEDGER_GET_CRED_DEF_FETCHER_ID
  lazy val cacheConfigPath: Option[String] = Option(LEDGER_GET_CRED_DEF_CACHE)

  //time to live in seconds, afterwards they will be considered as expired and re-fetched from source
  override lazy val defaultExpiryTimeInSeconds: Option[Int] = Option(300)
  override lazy val defaultMaxWeightInBytes: Option[Long] = Option(52428800) //50MB

  override def toKeyDetailMappings(keyDetails: Set[KeyDetail]): Set[KeyMapping] = {
    keyDetails.map { kd =>
      val gvp = kd.keyAs[GetCredDef]
      KeyMapping(kd, gvp.credDefId, gvp.credDefId)
    }
  }

  override def getByKeyDetail(kd: KeyDetail): Future[Map[String, AnyRef]] = {
    val getCredDef = kd.keyAs[GetCredDef]
    ledgerSvc.getCreDef(getCredDef.credDefId).map {
      case Right(resp) => Map(getCredDef.credDefId -> resp)
      case Left(d)     => throw buildUnexpectedResponse(d)
    }
  }
}

case class GetCredDef(credDefId: String)