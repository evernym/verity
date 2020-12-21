package com.evernym.verity.libindy.wallet

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

import com.evernym.verity.config.AppConfig
import com.evernym.verity.libindy.LibIndyCommon
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.evernym.verity.metrics.CustomMetrics._
import com.evernym.verity.metrics.MetricsWriter
import com.evernym.verity.vault.{WalletExt, _}
import com.typesafe.scalalogging.Logger
import org.hyperledger.indy.sdk.wallet._
import org.hyperledger.indy.sdk.{IOException, InvalidStateException}


class LibIndyWalletExt (val wallet: Wallet) extends WalletExt

class LibIndyWalletProvider(val appConfig: AppConfig) extends LibIndyCommon with WalletProvider {
  val logger: Logger = getLoggerByClass(classOf[LibIndyWalletProvider])

  def generateKey(seedOpt: Option[String] = None): String = {
    val conf = seedOpt.map(seed => s"""{"seed":"$seed"}""").getOrElse("""{}""")
    Wallet.generateWalletKey(conf).get
  }

  def createAndOpen(id: String, encryptionKey: String, walletConfig: WalletConfig): LibIndyWalletExt = {
    create(id, encryptionKey, walletConfig)
    openWithMaxTryCount(id, encryptionKey, walletConfig)(5)
  }

  def openWithMaxTryCount(id: String, encryptionKey: String, walletConfig: WalletConfig)
                         (maxTryCount: Int, curTryCount: Int = 1): LibIndyWalletExt = {
    try {
      logger.debug(s"open wallet attempt started ($curTryCount/$maxTryCount)")
      open(id, encryptionKey, walletConfig)
    } catch {
      case _: WalletInvalidState if curTryCount < maxTryCount =>
        MetricsWriter.gaugeApi.increment(AS_SERVICE_LIBINDY_WALLET_OPEN_ATTEMPT_FAILED_COUNT)
        Thread.sleep(50)
        openWithMaxTryCount(id, encryptionKey, walletConfig)(maxTryCount, curTryCount+1)
      case e: RuntimeException =>
        MetricsWriter.gaugeApi.increment(AS_SERVICE_LIBINDY_WALLET_OPEN_FAILED_AFTER_ALL_RETRIES_COUNT)
        throw e
    }
  }

  def create(id: String, encryptionKey: String, walletConfig: WalletConfig): Unit = {
    try {
      val startTime = LocalDateTime.now
      logger.debug(s"libindy api call started (create wallet)")
      Wallet.createWallet(
        walletConfig.buildConfig(id),
        walletConfig.buildCredentials(encryptionKey)).get
      val curTime = LocalDateTime.now
      val millis = ChronoUnit.MILLIS.between(startTime, curTime)
      logger.debug(s"libindy api call finished (create wallet), time taken (in millis): $millis")
    } catch {
      case e: Exception if e.getCause.isInstanceOf[WalletExistsException] =>
        throw WalletAlreadyExist("wallet already exists with name: " + id)
    }
  }

  def open(id: String, encryptionKey: String, walletConfig: WalletConfig): LibIndyWalletExt = {
    try {
      val startTime = LocalDateTime.now
      logger.debug(s"libindy api call started (open wallet)")
      val wallet = Wallet.openWallet(walletConfig.buildConfig(id),
        walletConfig.buildCredentials(encryptionKey)).get
      val curTime = LocalDateTime.now
      val millis = ChronoUnit.MILLIS.between(startTime, curTime)
      logger.debug(s"libindy api call finished (open wallet), time taken (in millis): $millis")
      new LibIndyWalletExt(wallet)
    } catch {
      case e: Exception if e.getCause.isInstanceOf[WalletAlreadyOpenedException] =>
        throw WalletAlreadyOpened(s"wallet already opened: '$id''")
      case e: Exception if e.getCause.isInstanceOf[WalletNotFoundException] =>
        throw WalletDoesNotExist(s"wallet does not exist: '$id'")
      case e: Exception if e.getCause.isInstanceOf[IOException] &&
        e.getCause.asInstanceOf[IOException].getSdkErrorCode == 114 =>
        throw WalletDoesNotExist(s"wallet/table does not exist: '$id'")
      case e: Exception if e.getCause.isInstanceOf[InvalidStateException] =>
        throw WalletInvalidState(s"error while opening wallet: '$id'")
      case e: Exception =>
        throw WalletNotOpened(s"error while opening wallet '$id': ${e.toString}")
    }
  }

  def checkIfWalletExists(walletName: String, encryptionKey: String, walletConfig: WalletConfig): Boolean = {
    try {
      close(open(walletName, encryptionKey, walletConfig))
      true
    } catch {
      case _: WalletDoesNotExist =>
        false
    }
  }

  def close(walletExt: WalletExt): Unit = {
    walletExt.wallet.closeWallet()
  }

  def delete(id: String, encryptionKey: String, walletConfig: WalletConfig): Unit = {
    try {
      Wallet.deleteWallet(walletConfig.buildConfig(id),
        walletConfig.buildCredentials(encryptionKey))
    } catch {
      //TODO: shall we catch only specific exception?
      case e: Exception => throw WalletNotDeleted(s"error while deleting wallet '$id' : ${e.toString}")
    }
  }

}
