package com.evernym.verity.testkit

import java.util.UUID
import java.util.concurrent.TimeUnit
import akka.actor.ActorSystem
import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.actor.wallet.{CreateWallet, WalletCommand, WalletCreated}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.util2.HasWalletExecutionContextProvider
import com.evernym.verity.libindy.wallet.LibIndyWalletProvider
import com.evernym.verity.observability.metrics.NoOpMetricsWriter
import com.evernym.verity.vault.{AgentWalletAPI, WalletAPIParam}
import com.evernym.verity.vault.service.ActorWalletService
import com.evernym.verity.vault.wallet_api.StandardWalletAPI

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{Await, ExecutionContext, Future}

/**
 * used to have a 'testWalletAPI' to be used in the 'test' code only
 * this 'testWalletAPI' is using LegacyWalletAPI (which is synchronous)
 */
trait HasTestWalletAPI extends HasWalletExecutionContextProvider {
  val testAppConfig: AppConfig = new TestAppConfig
  lazy val testWalletAPI: LegacyWalletAPI = {
    val walletProvider = LibIndyWalletProvider
    new LegacyWalletAPI(testAppConfig, walletProvider, None, NoOpMetricsWriter(), futureWalletExecutionContext)
  }
}

/**
 * a test wallet api along with standard agent wallet api (async) as well
 */
trait HasTestAgentWalletAPI extends HasTestWalletAPI with HasWalletExecutionContextProvider {
  def system: ActorSystem

  lazy val walletAPI = new StandardWalletAPI(new ActorWalletService(system, testAppConfig, futureWalletExecutionContext))
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
class TestWallet(walletExecutionContext: ExecutionContext, override val createWallet: Boolean = false) extends HasDefaultTestWallet {

  def executeSync[T](cmd: WalletCommand): T = {
    testWalletAPI.executeSync[T](cmd)(wap)
  }

  /**
   * custom thread pool executor
   */
  override def futureWalletExecutionContext: ExecutionContext = walletExecutionContext
}

trait AwaitResult {
  def convertToSyncReq[T](fut: Future[T]): T = {
    Await.result(fut, FiniteDuration(20, TimeUnit.SECONDS))
  }
}