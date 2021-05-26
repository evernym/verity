package com.evernym.verity.cache.fetchers

import com.evernym.verity.cache.base.{FetcherParam, KeyDetail, KeyMapping}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.CommonConfig._
import com.evernym.verity.ledger.LedgerSvc
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.Status.StatusDetailException
import com.evernym.verity.cache.LEDGER_GET_SCHEMA_FETCHER

import scala.concurrent.Future

class LedgerGetSchemaCacheFetcher(val ledgerSvc: LedgerSvc, val appConfig: AppConfig)
  extends AsyncCacheValueFetcher {

  lazy val fetcherParam: FetcherParam = LEDGER_GET_SCHEMA_FETCHER
  lazy val cacheConfigPath: Option[String] = Option(LEDGER_GET_SCHEMA_CACHE)

  //time to live in seconds, afterwards they will be considered as expired and re-fetched from source
  override lazy val defaultExpiryTimeInSeconds: Option[Int] = Option(300)
  override lazy val defaultMaxWeightInBytes: Option[Long] = Option(20971520) //20MB

  override def toKeyDetailMappings(keyDetails: Set[KeyDetail]): Set[KeyMapping] = {
    keyDetails.map { kd =>
      val gvp = kd.keyAs[GetSchema]
      KeyMapping(kd, gvp.schemaId, gvp.schemaId)
    }
  }

  override def getByKeyDetail(kd: KeyDetail): Future[Map[String, AnyRef]] = {
    val getSchema = kd.keyAs[GetSchema]
    ledgerSvc.getSchema(getSchema.schemaId).map { resp =>
      Map(getSchema.schemaId -> resp)
    }.recover {
      case StatusDetailException(sd) => throw buildUnexpectedResponse(sd)
    }
  }
}

case class GetSchema(schemaId: String)
