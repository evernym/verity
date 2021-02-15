package com.evernym.verity.cache.fetchers

import com.evernym.verity.Exceptions.BadRequestErrorException
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.Status._
import com.evernym.verity.cache.base.{KeyDetail, KeyMapping}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.CommonConfig._
import com.evernym.verity.constants.Constants._
import com.evernym.verity.ledger.{AttribResult, LedgerSvc, Submitter}
import com.evernym.verity.protocol.engine.DID

import scala.concurrent.Future


case class GetEndpointParam(did: DID, submitterDetail: Submitter) {
  override def toString: String = s"DID: $did, SubmitterDetail: $Submitter"
}

class EndpointCacheFetcher (val ledgerSvc: LedgerSvc, appConfig: AppConfig) extends AsyncCacheValueFetcher {

  lazy val id: Int = ENDPOINT_CACHE_FETCHER_ID

  //time to live in seconds, afterwards they will be considered as expired and re-fetched from source
  lazy val expiryTimeInSeconds: Option[Int] = Option(appConfig.getConfigIntOption(LEDGER_GET_ENDPOINT_CACHE_EXPIRATION_TIME_IN_SECONDS).getOrElse(1800))
  lazy val maxSize: Option[Int] = appConfig.getConfigIntOption(LEDGER_GET_ENDPOINT_CACHE_MAX_SIZE)

  override def toKeyDetailMappings(keyDetails: Set[KeyDetail]): Set[KeyMapping] = {
    keyDetails.map { kd =>
      val gvp = kd.key.asInstanceOf[GetEndpointParam]
      KeyMapping(kd, gvp.did, gvp.did)
    }
  }

  override def getByKeyDetail(kd: KeyDetail): Future[Map[String, Any]] = {
    val gep = kd.key.asInstanceOf[GetEndpointParam]
    val gepFut = ledgerSvc.getAttribFut(gep.submitterDetail, gep.did, ledgerSvc.URL)
    gepFut.map {
      case ar: AttribResult if ar.value.isDefined => Map(gep.did -> ar.value.map(_.toString).orNull)
      case ar: AttribResult if ar.value.isEmpty =>
        throw new BadRequestErrorException(DATA_NOT_FOUND.statusCode, Option("endpoint not found for DID: " + gep.did))
      case x => throw buildUnexpectedResponse(x)
    }
  }

}
