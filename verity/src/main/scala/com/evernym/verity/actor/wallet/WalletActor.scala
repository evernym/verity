package com.evernym.verity.actor.wallet

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

import akka.actor.Actor
import com.evernym.verity.actor.ActorMessageClass
import com.evernym.verity.actor.agent.SpanUtil.runWithInternalSpan
import com.evernym.verity.config.{AppConfig, AppConfigWrapper, ConfigUtil}
import com.evernym.verity.libindy.{LibIndyWalletExt, LibIndyWalletProvider}
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.evernym.verity.vault.WalletUtil.{buildWalletConfig, getWalletKeySeed, getWalletName}
import com.evernym.verity.vault.{WalletAccessParam, WalletAlreadyOpened, WalletDoesNotExist, WalletExt, WalletProvider}
import com.typesafe.scalalogging.Logger

import scala.concurrent.duration.Duration

case class DeleteWallet() extends ActorMessageClass
case class WalletDeleted() extends ActorMessageClass

class WalletActor(appConfig: AppConfig, walletProvider: WalletProvider = new LibIndyWalletProvider(AppConfigWrapper))
  extends Actor {

  val logger: Logger = getLoggerByClass(classOf[WalletActor])
  val defaultReceiveTimeoutInSeconds = 600
  def entityId: String = self.path.name
  def entityName: String = self.path.parent.name
//  def actorId: String = entityId
  var walletExt: WalletExt = null
  val encryptionKey = generateWalletKey()
  val walletConfig = buildWalletConfig(appConfig)
  val walletName = getWalletName(entityId, appConfig)

  def entityReceiveTimeout: Duration = ConfigUtil.getReceiveTimeout(
    appConfig, defaultReceiveTimeoutInSeconds,
    "base", entityName, entityId)

  def createWallet(): Unit = {
    runWithInternalSpan(s"createWallet", "WalletActor") {
      walletProvider.create(walletName, encryptionKey, walletConfig)
    }
  }

  def openWallet(): Unit = {
    runWithInternalSpan(s"openWallet", "WalletActor") {
      try {
        walletExt = walletProvider.open(walletName, encryptionKey, walletConfig)
      } catch {
        case e: WalletAlreadyOpened => logger.debug(e.message)
        case _: WalletDoesNotExist =>
          walletProvider.createAndOpen(walletName, encryptionKey, walletConfig)
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

  def generateWalletKey(): String =
    walletProvider.generateKey(Option(getWalletKeySeed(entityId, appConfig)))

  final override def receive = {
    case DeleteWallet() => {
      postStop()
      sender ! WalletDeleted()
    }
  }

  @throws(classOf[Exception])
  //#lifecycle-hooks
  override def preStart(): Unit = {
    context.setReceiveTimeout(entityReceiveTimeout)
    openWallet()
  }

  @throws(classOf[Exception])
  //#lifecycle-hooks
  override def postStop(): Unit = {
    closeWallet()
  }

}