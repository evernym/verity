package com.evernym.verity.vault

import java.util.UUID

import com.evernym.verity.constants.Constants._
import com.evernym.verity.config.CommonConfig.SALT_WALLET_ENCRYPTION
import com.evernym.verity.config.{AppConfig, CommonConfig}
import com.evernym.verity.ExecutionContextProvider.walletFutureExecutionContext
import com.evernym.verity.util.Util
import com.evernym.verity.vault.service.WalletParam
import org.apache.commons.codec.digest.DigestUtils

import scala.concurrent.Future

object WalletUtil {

  def buildWalletConfig(appConfig: AppConfig): WalletConfig = {
    if (appConfig.getConfigStringReq(CommonConfig.LIB_INDY_WALLET_TYPE) == WALLET_TYPE_MYSQL) {
      val readHost = appConfig.getConfigStringReq(CommonConfig.WALLET_STORAGE_READ_HOST)
      val writeHost = appConfig.getConfigStringReq(CommonConfig.WALLET_STORAGE_WRITE_HOST)
      val port = appConfig.getConfigIntReq(CommonConfig.WALLET_STORAGE_HOST_PORT)
      val userName = appConfig.getConfigStringReq(CommonConfig.WALLET_STORAGE_CRED_USERNAME)
      val password = appConfig.getConfigStringReq(CommonConfig.WALLET_STORAGE_CRED_PASSWORD)
      val dbName = appConfig.getConfigStringReq(CommonConfig.WALLET_STORAGE_DB_NAME)
      new MySqlWalletConfig(readHost, writeHost, port, userName, password, dbName)
    } else new DefaultWalletConfig
  }

  //TODO: there are some code duplicate in below methods, see if we can fix it
  def generateWalletParamAsync(walletId: String,
                          appConfig: AppConfig,
                          walletProvider: WalletProvider): Future[WalletParam] = {
    //TODO: should try to avoid this wallet config creating again and again
    val walletConfig = buildWalletConfig(appConfig)
    generateWalletParamAsync(walletId, appConfig, walletProvider, walletConfig)
  }

  private def generateWalletParamAsync(walletId: String,
                          appConfig: AppConfig,
                          walletProvider: WalletProvider,
                          walletConfig: WalletConfig): Future[WalletParam] = {
    val walletName = getWalletName(walletId, appConfig)
    walletProvider.generateKeyAsync(Option(getWalletKeySeed(walletId, appConfig))).map { key =>
      WalletParam(walletId, walletName, key, walletConfig)
    }
  }

  def generateWalletParamSync(walletId: String,
                              appConfig: AppConfig,
                              walletProvider: WalletProvider): WalletParam = {
    val walletConfig = buildWalletConfig(appConfig)
    generateWalletParamSync(walletId, appConfig, walletProvider, walletConfig)
  }

  def generateWalletParamSync(walletId: String,
                               appConfig: AppConfig,
                               walletProvider: WalletProvider,
                               walletConfig: WalletConfig): WalletParam = {
    val walletName = getWalletName(walletId, appConfig)
    val key = walletProvider.generateKeySync(Option(getWalletKeySeed(walletId, appConfig)))
    WalletParam(walletId, walletName, key, walletConfig)
  }

  private def getWalletName(entityId: String, appConfig: AppConfig): String = {
    //NOTE: This logic should not be changed unless we know its impact
    Util.saltedHashedName(entityId, appConfig)
  }

  private def getWalletKeySeed(id: String, appConfig: AppConfig): String = {
    //NOTE: This logic should not be changed unless we know its impact
    val salt = appConfig.getConfigStringReq(SALT_WALLET_ENCRYPTION)
    buildWalletKeySeed(id, salt)
  }

  private def buildWalletKeySeed(secret: String, salt: String): String = {
    //NOTE: This logic should not be changed unless we know its impact
    val seed = DigestUtils.sha512Hex(secret + salt)
    UUID.nameUUIDFromBytes(seed.getBytes).toString.replace("-", "")
  }
}
