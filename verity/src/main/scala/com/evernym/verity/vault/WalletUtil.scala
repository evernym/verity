package com.evernym.verity.vault

import java.util.UUID

import com.evernym.verity.constants.Constants._
import com.evernym.verity.config.CommonConfig.SALT_WALLET_ENCRYPTION
import com.evernym.verity.config.{AppConfig, CommonConfig}
import com.evernym.verity.util.Util
import org.apache.commons.codec.digest.DigestUtils

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

  def createWalletAccessParam(entityId: String, key: String, walletConfig: WalletConfig,
                              appConfig: AppConfig): WalletAccessParam =
    WalletAccessParam(
      getWalletName(entityId, appConfig),
      key,
      walletConfig
    )

  def getWalletName(entityId: String, appConfig: AppConfig): String = {
    //NOTE: This logic should not be changed unless we know its impact
    Util.saltedHashedName(entityId, appConfig)
  }

  def getWalletKeySeed(id: String, appConfig: AppConfig): String = {
    //NOTE: This logic should not be changed unless we know its impact
    val salt = appConfig.getConfigStringReq(SALT_WALLET_ENCRYPTION)
    buildWalletKeySeed(id, salt)
  }

  def buildWalletKeySeed(secret: String, salt: String): String = {
    //NOTE: This logic should not be changed unless we know its impact
    val seed = DigestUtils.sha512Hex(secret + salt)
    UUID.nameUUIDFromBytes(seed.getBytes).toString.replace("-", "")
  }

  def generateWalletKey(seed: String, walletAPI: WalletAPI, appConfig: AppConfig): String =
    walletAPI.generateWalletKey(Option(getWalletKeySeed(seed, appConfig)))
}
