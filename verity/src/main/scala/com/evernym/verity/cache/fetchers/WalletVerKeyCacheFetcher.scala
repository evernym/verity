package com.evernym.verity.cache.fetchers

import com.evernym.verity.actor.wallet.GetVerKeyOpt
import com.evernym.verity.cache.base.{KeyDetail, KeyMapping}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.CommonConfig._
import com.evernym.verity.constants.Constants._
import com.evernym.verity.protocol.engine.{DID, VerKey}
import com.evernym.verity.vault._
import com.evernym.verity.vault.wallet_api.WalletAPI


case class GetWalletVerKeyParam(did: DID, getFromPool: Boolean = false, wap: WalletAPIParam) {
  override def toString: String = s"DID: $did, getKeyFromPool: $getFromPool"
}

class WalletVerKeyCacheFetcher(val walletAPI: WalletAPI, appConfig: AppConfig) extends SyncCacheValueFetcher {

  lazy val id: Int = WALLET_VER_KEY_CACHE_FETCHER_ID

  //time to live in seconds, afterwards they will be considered as expired and re-fetched from source
  lazy val expiryTimeInSeconds: Option[Int] = Option(appConfig.getConfigIntOption(WALLET_VER_KEY_CACHE_EXPIRATION_TIME_IN_SECONDS).getOrElse(1800))
  lazy val maxSize: Option[Int] = appConfig.getConfigIntOption(WALLET_VER_KEY_CACHE_MAX_SIZE)

  override def toKeyDetailMappings(keyDetails: Set[KeyDetail]): Set[KeyMapping] = {
    keyDetails.map { kd =>
      val gvp = kd.key.asInstanceOf[GetWalletVerKeyParam]
      KeyMapping(kd, gvp.did, gvp.did)
    }
  }

  override def getByKeyDetail(kd: KeyDetail): Map[String, Any] = {
    val gvp = kd.key.asInstanceOf[GetWalletVerKeyParam]
    val verKeyOpt = walletAPI.executeSync[Option[VerKey]](
      GetVerKeyOpt(gvp.did, gvp.getFromPool))(gvp.wap)
    val result: Option[Map[String, Any]] = verKeyOpt.map(vk => Map(gvp.did -> vk))
    result.getOrElse(Map.empty)
  }

}
