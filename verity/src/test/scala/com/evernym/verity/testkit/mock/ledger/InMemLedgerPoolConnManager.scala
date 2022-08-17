package com.evernym.verity.testkit.mock.ledger

import com.evernym.verity.actor.testkit.actor.MockLedgerTxnExecutor
import com.evernym.verity.config.AppConfig
import com.evernym.verity.ledger.{LedgerPoolConnManager, LedgerTxnExecutor}
import com.evernym.verity.vault.wallet_api.WalletAPI
import com.evernym.verity.vdr.VDRAdapter

import scala.concurrent.ExecutionContext

class InMemLedgerPoolConnManager(val executionContext: ExecutionContext,
                                 appConfig: AppConfig,
                                 vdrAdapter: VDRAdapter,
                                 txnExecutor: Option[LedgerTxnExecutor] = None)
  extends LedgerPoolConnManager {

  def this(executionContext: ExecutionContext, appConfig: AppConfig, vdrAdapter: VDRAdapter) =  {
    this(executionContext, appConfig, vdrAdapter, Some(new MockLedgerTxnExecutor(executionContext, appConfig, vdrAdapter)))
  }

  val ledgerTxnExecutor: LedgerTxnExecutor = {
    txnExecutor.getOrElse(new MockLedgerTxnExecutor(executionContext, appConfig, vdrAdapter))
  }

  override def open(): Unit = ()

  override def close(): Unit = ()

  override def isConnected: Boolean = true

  override def deletePoolLedgerConfig(): Unit = ()

  override def txnExecutor(walletAPI: Option[WalletAPI]): LedgerTxnExecutor = ledgerTxnExecutor

  override def connHandle: Option[Int] = Some(10)
}