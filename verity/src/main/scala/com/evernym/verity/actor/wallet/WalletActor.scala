package com.evernym.verity.actor.wallet

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

import akka.actor.Actor
import com.evernym.verity.actor.ActorMessageClass
import com.evernym.verity.actor.agent.SpanUtil.runWithInternalSpan
import com.evernym.verity.config.AppConfigWrapper
import com.evernym.verity.libindy.LibIndyWalletProvider
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.evernym.verity.vault.{WalletAccessParam, WalletExt, WalletProvider}
import com.typesafe.scalalogging.Logger

case class CreateWallet(wap: WalletAccessParam) extends ActorMessageClass
case class OpenWallet(wap: WalletAccessParam) extends ActorMessageClass
case class CloseWallet(walletExt: WalletExt) extends ActorMessageClass

class WalletActor(walletProvider: WalletProvider = new LibIndyWalletProvider(AppConfigWrapper)) extends Actor {

  val logger: Logger = getLoggerByClass(classOf[WalletActor])

  def createWallet(wap: WalletAccessParam): Unit = {
    runWithInternalSpan(s"createWallet", "runWithInternalSpan") {
      walletProvider.create(wap.walletName, wap.encryptionKey, wap.walletConfig)
    }
  }

  def openWallet(implicit wap: WalletAccessParam): WalletExt = {
    runWithInternalSpan(s"openWallet", "runWithInternalSpan") {
      walletProvider.open(wap.walletName, wap.encryptionKey, wap.walletConfig)
    }
  }

  def closeWallet(walletExt: WalletExt): Unit = {
    runWithInternalSpan(s"closeWallet", "runWithInternalSpan") {
      walletProvider.close(walletExt)
    }
  }

  final override def receive = {
    case CreateWallet(wap) => this.createWallet(wap)
    case OpenWallet(wap) => this.openWallet(wap)
    case CloseWallet(walletExt) => this.closeWallet(walletExt)
  }

}