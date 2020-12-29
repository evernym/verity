package com.evernym.verity.vault

import com.evernym.verity.actor.wallet.WalletCreated

import scala.concurrent.Future

trait WalletProvider {

  def createAsync(id: String, encryptionKey: String, walletConfig: WalletConfig): Future[WalletCreated.type]

  def openAsync(id: String, encryptionKey: String, walletConfig: WalletConfig): Future[WalletExt]

  def createSync(id: String, encryptionKey: String, walletConfig: WalletConfig): WalletCreated.type

  def openSync(id: String, encryptionKey: String, walletConfig: WalletConfig): WalletExt

  def generateKeyAsync(seedOpt: Option[String] = None): Future[String]

  def generateKeySync(seedOpt: Option[String] = None): String

  def close(walletExt: WalletExt): Unit
}
