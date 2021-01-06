package com.evernym.verity.libindy.wallet

import com.evernym.verity.actor.wallet.WalletCreated
import com.evernym.verity.config.AppConfig
import com.evernym.verity.libindy.LibIndyCommon
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.libindy.wallet.operation_executor.FutureConverter
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.evernym.verity.protocol.engine.util.?=>
import com.evernym.verity.vault.{WalletExt, _}
import com.typesafe.scalalogging.Logger
import org.hyperledger.indy.sdk.wallet._
import org.hyperledger.indy.sdk.{IOException, InvalidStateException}

import scala.concurrent.Future


class LibIndyWalletExt (val wallet: Wallet) extends WalletExt

class LibIndyWalletProvider(val appConfig: AppConfig)
  extends LibIndyCommon
    with WalletProvider
    with FutureConverter {
  val logger: Logger = getLoggerByClass(classOf[LibIndyWalletProvider])

  //TODO: there are some code duplicate in below methods, see if we can fix it

  def generateKeySync(seedOpt: Option[String] = None): String = {
    val conf = seedOpt.map(seed => s"""{"seed":"$seed"}""").getOrElse("""{}""")
    Wallet.generateWalletKey(conf).get
  }

  def generateKeyAsync(seedOpt: Option[String] = None): Future[String] = {
    val conf = seedOpt.map(seed => s"""{"seed":"$seed"}""").getOrElse("""{}""")
    Wallet.generateWalletKey(conf)
  }

  def createSync(id: String, encryptionKey: String, walletConfig: WalletConfig): WalletCreated.type = {
    try {
      Wallet.createWallet(
        walletConfig.buildConfig(id),
        walletConfig.buildCredentials(encryptionKey)).get
      WalletCreated
    } catch {
      case e: Exception if e.getCause.isInstanceOf[WalletExistsException] =>
        throw WalletAlreadyExist("wallet already exists with name: " + id)
    }
  }

  def createAsync(id: String, encryptionKey: String, walletConfig: WalletConfig): Future[WalletCreated.type] = {
    Wallet.createWallet(
      walletConfig.buildConfig(id),
      walletConfig.buildCredentials(encryptionKey))
      .map(_ => WalletCreated)
      .recover {
        case e: Exception if e.getCause.isInstanceOf[WalletExistsException] =>
          throw WalletAlreadyExist("wallet already exists with name: " + id)
      }
  }

  def openAsync(id: String, encryptionKey: String, walletConfig: WalletConfig): Future[LibIndyWalletExt] = {
    Wallet.openWallet(walletConfig.buildConfig(id),
        walletConfig.buildCredentials(encryptionKey))
    .map { wallet =>
      new LibIndyWalletExt(wallet)
    }.recover {
      openExceptionHandler(id) andThen (throw _)
    }
  }

  def openSync(id: String, encryptionKey: String, walletConfig: WalletConfig): LibIndyWalletExt = {
    try {
      val wallet = Wallet.openWallet(walletConfig.buildConfig(id),
        walletConfig.buildCredentials(encryptionKey)).get
      new LibIndyWalletExt(wallet)
    } catch openExceptionHandler(id) andThen { throw _ }
  }

  def openExceptionHandler(id: String): Throwable ?=> RuntimeException = {
    case e: Exception if e.getCause.isInstanceOf[WalletAlreadyOpenedException] =>
      WalletAlreadyOpened(s"wallet already opened: '$id''")
    case e: Exception if e.getCause.isInstanceOf[WalletNotFoundException] =>
      WalletDoesNotExist(s"wallet does not exist: '$id'")
    case e: Exception if e.getCause.isInstanceOf[IOException] &&
      e.getCause.asInstanceOf[IOException].getSdkErrorCode == 114 =>
      WalletDoesNotExist(s"wallet/table does not exist: '$id'")
    case e: Exception if e.getCause.isInstanceOf[InvalidStateException] =>
      WalletInvalidState(s"error while opening wallet: '$id'")
    case e: Exception =>
      WalletNotOpened(s"error while opening wallet '$id': ${e.toString}")
  }

  def close(walletExt: WalletExt): Unit = {
    walletExt.wallet.closeWallet()
  }

}
