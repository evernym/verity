package com.evernym.verity.protocol.legacy.services

import com.evernym.verity.Exceptions.BadRequestErrorException
import com.evernym.verity.Status.DATA_NOT_FOUND
import com.evernym.verity.cache._
import com.evernym.verity.config.AppConfig
import com.evernym.verity.constants.Constants.WALLET_VER_KEY_CACHE_FETCHER_ID
import com.evernym.verity.protocol.engine.{DID, VerKey}
import com.evernym.verity.vault.WalletAPIParam
import com.evernym.verity.vault.wallet_api.WalletAPI

/**
 * used by protocol and tests
 */
class WalletVerKeyCacheHelper(wap: WalletAPIParam, walletAPI: WalletAPI, appConfig: AppConfig) {

  private lazy val walletCacheFetchers: Map[Int, CacheValueFetcher] = Map (
    WALLET_VER_KEY_CACHE_FETCHER_ID -> new WalletVerKeyCacheFetcher(walletAPI, appConfig)
  )

  private lazy val walletCache = new Cache("WC", walletCacheFetchers)

  def getVerKeyViaCache(did: DID, req: Boolean = false, getKeyFromPool: Boolean = false): Option[VerKey] = {
    val gcop = GetCachedObjectParam(Set(KeyDetail(GetWalletVerKeyParam(did, getKeyFromPool, wap), required = req)),
      WALLET_VER_KEY_CACHE_FETCHER_ID)
    walletCache.getByParamSync(gcop).getStringOpt(did)
  }

  def getVerKeyReqViaCache(did: DID, getKeyFromPool: Boolean = false): VerKey = {
    getVerKeyViaCache(did, req = true, getKeyFromPool).getOrElse(
      throw new BadRequestErrorException(DATA_NOT_FOUND.statusCode, Option(s"ver key not found for DID: $did")))
  }

}
