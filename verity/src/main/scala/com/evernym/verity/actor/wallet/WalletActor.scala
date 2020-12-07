package com.evernym.verity.actor.wallet

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

import akka.actor.Actor
import com.evernym.verity.actor.ActorMessageClass
import com.evernym.verity.actor.agent.SpanUtil.runWithInternalSpan
import com.evernym.verity.config.{AppConfigWrapper, ConfigUtil}
import com.evernym.verity.libindy.{LibIndyWalletExt, LibIndyWalletProvider}
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.evernym.verity.vault.{WalletAccessParam, WalletAlreadyOpened, WalletDoesNotExist, WalletExt, WalletProvider}
import com.typesafe.scalalogging.Logger

import scala.concurrent.duration.Duration

case class DeleteWallet() extends ActorMessageClass

class WalletActor(walletProvider: WalletProvider = new LibIndyWalletProvider(AppConfigWrapper))
  extends Actor {

  val logger: Logger = getLoggerByClass(classOf[WalletActor])
  var walletExt: WalletExt = null
  var wap: WalletAccessParam = null

  def entityReceiveTimeout: Duration = ConfigUtil.getReceiveTimeout(
    appConfig, defaultReceiveTimeoutInSeconds,
    normalizedEntityCategoryName, normalizedEntityName, normalizedEntityId)

  def createWallet(wap: WalletAccessParam): Unit = {
    runWithInternalSpan(s"createWallet", "WalletActor") {
      walletProvider.create(wap.walletName, wap.encryptionKey, wap.walletConfig)
    }
  }

  def openWallet(wap: WalletAccessParam): Unit = {
    runWithInternalSpan(s"openWallet", "WalletActor") {
      try {
        walletExt = walletProvider.open(wap.walletName, wap.encryptionKey, wap.walletConfig)
      } catch {
        case e: WalletAlreadyOpened => logger.debug(e.message)
        case _: WalletDoesNotExist =>
          walletProvider.createAndOpen(wap.walletName, wap.encryptionKey, wap.walletConfig)
      }
    }
  }

  def closeWallet(): Unit = {
    if (walletExt == null) {
      logger.debug("WalletActor try to close not opened wallet")
      return
    }
    runWithInternalSpan(s"closeWallet", "WalletActor") {
      walletProvider.close(walletExt)
    }
  }

  final override def receive = {
    case DeleteWallet() => postStop()
  }

  @throws(classOf[Exception])
  //#lifecycle-hooks
  override def preStart(): Unit = {
    context.setReceiveTimeout(entityReceiveTimeout)
    openWallet(wap)
  }

  @throws(classOf[Exception])
  //#lifecycle-hooks
  override def postStop(): Unit = {
    closeWallet()
  }

}