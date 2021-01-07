package com.evernym.verity.vault.wallet_api.base

import java.util.UUID

import akka.actor.ActorRef
import com.evernym.verity.actor.testkit.actor.ProvidesMockPlatform
import com.evernym.verity.actor.testkit.{ActorSpec, CommonSpecUtil}
import com.evernym.verity.actor.wallet._
import com.evernym.verity.metrics.MetricsReader
import com.evernym.verity.protocol.engine.VerKey
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.vault.wallet_api.WalletAPI
import com.evernym.verity.vault.{GetVerKeyByDIDParam, KeyParam, WalletAPIParam}
import com.typesafe.config.{Config, ConfigFactory}

import scala.concurrent.{ExecutionContext, Future}

trait ClientWalletAPISpecBase
  extends ActorSpec
    with ProvidesMockPlatform
    with UserWalletSetupHelper
    with HasThreadStarvationDetector
    with BasicSpec {

  //to start metrics reporter
  MetricsReader

  //execution context to be used to create futures in the test code
  // this execution context will also be checked for thread starvation
  // if corresponding code is enabled in 'HasThreadStarvationDetector'
  // keep overriding in implementing class as needed
  implicit def testCodeExecutionContext: ExecutionContext = {
    com.evernym.verity.ExecutionContextProvider.futureExecutionContext
    //comment above and uncomment/modify below to use custom thread pool
    //ExecutionContext.fromExecutor(Executors.newFixedThreadPool(10))
  }

  ////NOTE: enable below function call to check for thread starvation for 'testCodeExecutionContext'
  //checkThreadStarvationFor(testCodeExecutionContext)

  def totalUsers: Int
  def startUserWalletSetupWithSyncAPI(): Unit
  def startUserWalletSetupWithAsyncAPI(): Unit
  def waitForAllResponses(): Unit

  var successResp: Int = 0
  var failedResp: Int = 0
  def totalRespCount: Int = successResp + failedResp

  lazy val libIndyMetricsCollector: ActorRef = platform.libindyMetricsCollector

  def printExecutorMetrics(): Unit = {
    //libIndyMetricsCollector ! CollectLibindyMetrics()
    val metrics =  MetricsReader.getNodeMetrics().metrics
    val filteredMetric = metrics
      .filter(m => Set("executor").exists(m.name.contains) )
    //println("filtered metrics: ====>\n" + filteredMetric.map(m => s"${m.name}:${m.value} (${m.tags})").mkString("\n"))
  }

  override def overrideConfig: Option[Config] = Option {
    walletAPIConfig.withFallback(walletStorageConfig)
  }

  def walletAPIConfig: Config = ConfigFactory parseString {
    """
      verity.wallet-api = "standard"                   # use "legacy" to test 'legacy wallet api'
      """
  }

  //for file based wallet nothing needs to be set
  def walletStorageConfig: Config = ConfigFactory.parseString("""""")
}

//------------- helper classes/traits

trait UserWalletSetupHelper {

  protected def _baseWalletSetupWithSyncAPI(userId: Int, walletAPI: WalletAPI): Unit = {
    implicit val wap: WalletAPIParam = WalletAPIParam(UUID.randomUUID().toString)
    println(s"[$userId] about to start executing wallet operations for an user")
    val wc = walletAPI.createWallet(wap)
    println(s"[$userId] wallet created")
    val nkc = walletAPI.createNewKey(CreateNewKey())
    println(s"[$userId] new key created")
    val theirDidPair = CommonSpecUtil.generateNewDid()
    val stk = walletAPI.storeTheirKey(StoreTheirKey(theirDidPair.DID, theirDidPair.verKey))
    println(s"[$userId] their key stored")
    val gvk = walletAPI.getVerKeyOption(GetVerKeyOpt(KeyParam(Right(GetVerKeyByDIDParam(nkc.did, getKeyFromPool = false)))))
    println(s"[$userId] ver key retrieved")
  }

  protected def _baseWalletSetupWithAsyncAPI(userId: Int, walletAPI: WalletAPI)
                                            (implicit ec: ExecutionContext): Future[Any] = {
    implicit val wap: WalletAPIParam = WalletAPIParam(UUID.randomUUID().toString)
    println(s"[$userId] about to start executing wallet operations for an user")
    val result = for (
      _ <- walletAPI.executeAsync[WalletCreated.type](CreateWallet)
    ) yield {
      println(s"[$userId] wallet created")

      val fut1 = walletAPI.executeAsync[NewKeyCreated](CreateNewKey()).map { _ =>
        println(s"[$userId] new key created")
      }

      val theirDidPair = CommonSpecUtil.generateNewDid()
      val fut2 = walletAPI.executeAsync[TheirKeyStored](StoreTheirKey(theirDidPair.DID, theirDidPair.verKey)).map { r =>
        println(s"[$userId] their key stored")
        r
      }

      val fut3 = walletAPI.executeAsync[Option[VerKey]](
        GetVerKeyOpt(KeyParam(Right(GetVerKeyByDIDParam(theirDidPair.DID, getKeyFromPool = false))))).map { r =>
        println(s"[$userId] ver key retrieved")
        r
      }
      Future.sequence(Seq(fut1, fut2))
        .flatMap { _ =>
          fut3
        }
    }
    result.flatten
  }
}