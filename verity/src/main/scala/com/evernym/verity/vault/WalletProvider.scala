package com.evernym.verity.vault

trait WalletProvider {

  def generateKey(seedOpt: Option[String] = None): String

  def createAndOpen(id: String, encryptionKey: String, walletConfig: WalletConfig): WalletExt

  def create(id: String, encryptionKey: String, walletConfig: WalletConfig): Unit

  def open(id: String, encryptionKey: String, walletConfig: WalletConfig): WalletExt

  def checkIfWalletExists(id: String, encryptionKey: String, walletConfig: WalletConfig): Boolean

  def close(walletExt: WalletExt): Unit

  def delete(id: String, encryptionKey: String, walletConfig: WalletConfig): Unit
}
