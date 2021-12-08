package com.evernym.verity.vdrtools.wallet

import com.evernym.verity.actor.wallet.WalletCreated
import com.evernym.verity.vault.operation_executor.FutureConverter
import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByClass
import com.evernym.verity.protocol.engine.util.?=>
import com.evernym.verity.vault.{WalletExt, _}
import com.typesafe.scalalogging.Logger
import com.evernym.vdrtools.wallet._
import com.evernym.vdrtools.{IOException, InvalidStateException}

import scala.concurrent.ExecutionContext
import scala.concurrent.Future


class LibIndyWalletExt (val wallet: Wallet) extends WalletExt

object LibIndyWalletProvider
  extends WalletProvider
    with FutureConverter {
  val logger: Logger = getLoggerByClass(getClass)

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
    } catch handleWalletEx(id) andThen { throw _ }
  }

  def createAsync(id: String, encryptionKey: String, walletConfig: WalletConfig)(implicit ec: ExecutionContext): Future[WalletCreated.type] = {
    Wallet.createWallet(
      walletConfig.buildConfig(id),
      walletConfig.buildCredentials(encryptionKey))
      .map(_ => WalletCreated)
      .recover {
        handleWalletEx(id) andThen { throw _ }
      }
  }

  def openAsync(id: String, encryptionKey: String, walletConfig: WalletConfig)(implicit ec: ExecutionContext): Future[LibIndyWalletExt] = {
    Wallet.openWallet(walletConfig.buildConfig(id),
        walletConfig.buildCredentials(encryptionKey))
    .map { wallet =>
      new LibIndyWalletExt(wallet)
    }.recover {
      handleWalletEx(id) andThen { throw _ }
    }
  }

  def openSync(id: String, encryptionKey: String, walletConfig: WalletConfig): LibIndyWalletExt = {
    try {
      val wallet = Wallet.openWallet(walletConfig.buildConfig(id),
        walletConfig.buildCredentials(encryptionKey)).get
      new LibIndyWalletExt(wallet)
    } catch handleWalletEx(id) andThen { throw _ }
  }

  def close(walletExt: WalletExt): Unit = {
    walletExt.wallet.closeWallet()
  }

  private def handleWalletEx(id: String): Throwable ?=> RuntimeException = {
    case e: Exception if walletExceptionMapper(id).isDefinedAt(e.getCause) =>
      walletExceptionMapper(id)(e.getCause)
    case e: Exception if walletExceptionMapper(id).isDefinedAt(e) =>
      walletExceptionMapper(id)(e)
    case e: Exception =>
      WalletNotOpened(s"error during wallet operation '$id' (${e.getMessage})")
  }

  private def walletExceptionMapper(id: String): Throwable ?=> RuntimeException = {
    case e: WalletExistsException =>
      WalletAlreadyExist(s"wallet already exists: '$id' (${e.getMessage})")
    case e: WalletAlreadyOpenedException =>
      WalletAlreadyOpened(s"wallet already opened: '$id' (${e.getMessage})")
    case e: WalletNotFoundException =>
      WalletDoesNotExist(s"wallet does not exist: '$id' (${e.getMessage})")
    case e: IOException if e.getSdkErrorCode == 114 =>
      WalletDoesNotExist(s"wallet/table does not exist: '$id' (${e.getMessage})")
    case e: InvalidStateException =>
      WalletInvalidState(s"wallet is in invalid state: '$id' (${e.getMessage})")
  }

}
