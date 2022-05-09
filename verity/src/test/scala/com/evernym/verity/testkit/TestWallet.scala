package com.evernym.verity.testkit

import akka.actor.ActorSystem
import com.evernym.verity.actor.testkit.{HasActorSystem, TestAppConfig}
import com.evernym.verity.actor.wallet.{CreateWallet, WalletCommand, WalletCreated}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.ledger.LedgerPoolConnManager
import com.evernym.verity.util2.HasExecutionContextProvider
import com.evernym.verity.vault.service.ActorWalletService
import com.evernym.verity.vault.wallet_api.StandardWalletAPI
import com.evernym.verity.vault.{AgentWalletAPI, WalletAPIParam}
import com.evernym.verity.vdrtools.ledger.IndyLedgerPoolConnManager

import java.util.UUID
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{Await, ExecutionContext, Future}

/**
 * used to have a 'testWalletAPI' to be used in the 'test' code only
 * this 'testWalletAPI' is using LegacyWalletAPI (which is synchronous)
 */
trait HasTestWalletAPI extends HasExecutionContextProvider with HasActorSystem{
  val testAppConfig: AppConfig = new TestAppConfig

  val ledgerPoolConnManager: LedgerPoolConnManager = new IndyLedgerPoolConnManager(system, testAppConfig, futureExecutionContext)
  lazy val testWalletAPI: StandardWalletAPI = {
    new StandardWalletAPI(new ActorWalletService(system, testAppConfig,  ledgerPoolConnManager, futureExecutionContext))
  }
}

/**
 * a test wallet api along with standard agent wallet api (async) as well
 */
trait HasTestAgentWalletAPI extends HasTestWalletAPI with HasExecutionContextProvider {

  lazy val walletAPI = new StandardWalletAPI(new ActorWalletService(system, testAppConfig, ledgerPoolConnManager, futureExecutionContext))
  def agentWalletAPI(walletId: String): AgentWalletAPI = AgentWalletAPI(walletAPI, walletId)
  def agentWalletAPI(implicit wap: WalletAPIParam): AgentWalletAPI = AgentWalletAPI(walletAPI, wap.walletId)
}

/**
 * has a 'test wallet API' with a default wallet id
 */
trait HasDefaultTestWallet extends HasTestWalletAPI {
  def createWallet: Boolean = false

  val walletId: String = UUID.randomUUID().toString

  implicit val wap: WalletAPIParam = WalletAPIParam(walletId)

  if (createWallet) {
    testWalletAPI.executeSync[WalletCreated.type](CreateWallet())
  }
}

/**
 * a test wallet to be generally used when interacting with more than one wallets in same tests
 * @param createWallet determines if wallet should get created as part of this object creation
 */
class TestWallet(executionContext: ExecutionContext,
                 override val createWallet: Boolean = false,
                 override implicit val system: ActorSystem) extends HasDefaultTestWallet {

  def executeSync[T](cmd: WalletCommand): T = {
    testWalletAPI.executeSync[T](cmd)(wap)
  }

  /**
   * custom thread pool executor
   */
  override def futureExecutionContext: ExecutionContext = executionContext
}

trait AwaitResult {
  def convertToSyncReq[T](fut: Future[T]): T = {
    Await.result(fut, FiniteDuration(20, TimeUnit.SECONDS))
  }
}