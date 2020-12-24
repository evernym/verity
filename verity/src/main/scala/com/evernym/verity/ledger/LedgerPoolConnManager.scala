package com.evernym.verity.ledger

import com.evernym.verity.config.AppConfig
import com.evernym.verity.vault.wallet_api.WalletAPI

class LedgerPoolException(msg: String) extends Exception(msg)
case class OpenConnException(msg: String) extends LedgerPoolException(msg)

abstract class ConfigurableLedgerPoolConnManager(val appConfig: AppConfig) extends LedgerPoolConnManager {

}

trait LedgerPoolConnManager {

  def open(): Unit

  def close(): Unit

  def connHandle: Option[Int]

  def isConnected: Boolean

  def deletePoolLedgerConfig(): Unit

  def txnExecutor(walletAPI: Option[WalletAPI]): LedgerTxnExecutor

  var currentTAA: Option[TransactionAuthorAgreement] = None

}