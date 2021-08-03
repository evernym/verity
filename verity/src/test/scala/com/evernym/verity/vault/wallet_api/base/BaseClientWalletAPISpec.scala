package com.evernym.verity.vault.wallet_api.base

import java.util.UUID
import akka.actor.ActorRef
import com.evernym.verity.actor.testkit.actor.ProvidesMockPlatform
import com.evernym.verity.actor.testkit.{ActorSpec, CommonSpecUtil}
import com.evernym.verity.actor.wallet._
import com.evernym.verity.testkit.{BasicSpec, HasTestWalletAPI}
import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.vault.wallet_api.WalletAPI
import com.evernym.verity.vault.WalletAPIParam
import com.evernym.verity.util2.HasExecutionContextProvider
import com.typesafe.config.{Config, ConfigFactory}

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{ExecutionContext, Future}

trait ClientWalletAPISpecBase
  extends ActorSpec
    with ProvidesMockPlatform
    with UserWalletSetupHelper
    with HasThreadStarvationDetector
    with HasTestWalletAPI
    with BasicSpec
    with HasExecutionContextProvider {

  //execution context to be used to create futures in the test code
  // this execution context will also be checked for thread starvation
  // if corresponding code is enabled in 'HasThreadStarvationDetector'
  // keep overriding in implementing class as needed
  implicit val testCodeExecutionContext: ExecutionContext = futureExecutionContext
    //comment above and uncomment/modify below to use custom thread pool
    //ExecutionContext.fromExecutor(Executors.newFixedThreadPool(10))

  ////NOTE: enable below function call to check for thread starvation for 'testCodeExecutionContext'
  //checkThreadStarvationFor(testCodeExecutionContext)

  def totalUsers: Int
  def startUserWalletSetupWithAsyncAPI(): Unit
  def waitForAllResponses(): Unit

  val successResp = new AtomicInteger(0)
  val failedResp = new AtomicInteger(0)
  def totalRespCount: Int = successResp.get() + failedResp.get()

  lazy val libIndyMetricsCollector: ActorRef = platform.libIndyMetricsCollector

  override def overrideConfig: Option[Config] = Option {
    ConfigFactory.parseString(
      """
        |verity.metrics.enabled = N
        |""".stripMargin
    )
      .withFallback(walletStorageConfig)
  }

  //for file based wallet nothing needs to be set
  def walletStorageConfig: Config = ConfigFactory.empty()
}

//------------- helper classes/traits

trait UserWalletSetupHelper {

  protected def _baseWalletSetupWithAsyncAPI(walletAPI: WalletAPI)
                                            (implicit ec: ExecutionContext): Future[Any] = {
    implicit val wap: WalletAPIParam = WalletAPIParam(UUID.randomUUID().toString)
    val result = for (
      _ <- walletAPI.executeAsync[WalletCreated.type](CreateWallet())
    ) yield {
      val theirDidPair = CommonSpecUtil.generateNewDid()
      val fut1 = walletAPI.executeAsync[NewKeyCreated](CreateNewKey())
      val fut2 =
        walletAPI.executeAsync[TheirKeyStored](StoreTheirKey(theirDidPair.DID, theirDidPair.verKey)).map { _ =>
          walletAPI.executeAsync[GetVerKeyOptResp](GetVerKeyOpt(theirDidPair.DID))
        }
      Future.sequence(Seq(fut1, fut2))
    }
    result.flatten
  }
}