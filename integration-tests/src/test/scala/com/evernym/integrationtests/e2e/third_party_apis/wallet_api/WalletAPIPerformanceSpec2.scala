package com.evernym.integrationtests.e2e.third_party_apis.wallet_api

import java.io.File
import java.util.UUID

import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.actor.testkit.{ActorSpec, CommonSpecUtil}
import com.evernym.verity.actor.wallet._
import com.evernym.verity.actor.testkit.actor.ProvidesMockPlatform
import com.evernym.verity.metrics.MetricsReader
import com.evernym.verity.protocol.engine.VerKey
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.vault.{GetVerKeyByDIDParam, KeyParam, WalletAPIParam}
import com.typesafe.config.{Config, ConfigFactory}

import scala.concurrent.Future

/**
 * this is an integration test and it depends on mysql based wallet storage
 * mysql can be installed by executing        : <repo-root>/devops/scripts/wallet-storage-mysql/install.sh
 * required tables can be setup by executing  : <repo-root>/devops/scripts/wallet-storage-mysql/clean-setup.sh
 */


//NOTE: this one exercises "ASYNC wallet api"
class WalletAPIPerformanceSpec2
  extends ActorSpec
    with ProvidesMockPlatform
    with BasicSpec {

  MetricsReader

  var totalUser: Int = 1000
  var successResp: Int = 0

  "WalletService" - {
    "when tried to setup lots of user wallets parallely" - {
      "should be successful" in {
        (1 to totalUser).foreach { _ =>
          Future(setupUser(UUID.randomUUID().toString))
        }

        //wait until all user wallet setup is completed
        while (successResp < totalUser) {
          printExecutorMetrics()
          println("wallet setup completed count                             : " + successResp)
          Thread.sleep(5000)
        }
        println("final wallet setup completed count                         : " + successResp)
      }
    }
  }

  /**
   * purpose of this method is to setup a new user wallet (and execute 3-4 wallet operations against it)
   *
   * @param id
   */
  def setupUser(id: String): Unit = {
    implicit val wap: WalletAPIParam = WalletAPIParam(id)
    val ddPair = CommonSpecUtil.generateNewDid()
    println(s"[$id] about to start executing wallet operations for an user")
    for (
      _ <- walletAPI.executeAsync[WalletCreated.type](CreateWallet)
    ) yield {
      println(s"[$id] wallet created")
      val fut1 = walletAPI.executeAsync[NewKeyCreated](CreateNewKey())
      val fut2 = walletAPI.executeAsync[TheirKeyStored](StoreTheirKey(ddPair.DID, ddPair.verKey))
      val fut3 = walletAPI.executeAsync[Option[VerKey]](GetVerKeyOpt(KeyParam(Right(GetVerKeyByDIDParam(ddPair.DID, getKeyFromPool = false)))))
      Future.sequence(Seq(fut1, fut2))
        .map { _ =>
          fut3
        }.map { _ =>
        successResp += 1
      }
    }
  }

  override def overrideConfig: Option[Config] = Option {
    walletAPIConfig.withFallback(walletStorageConfig)
  }

  def walletAPIConfig: Config = ConfigFactory parseString {
    """
      verity.wallet-api = "standard"                   # use "legacy" to test 'legacy wallet api'
      verity.lib-indy.wallet.type = "mysql"
      """
  }

  def walletStorageConfig: Config =
    ConfigFactory.parseFile(new File("verity/src/main/resources/wallet-storage.conf"))
      .resolve()

  def printExecutorMetrics(): Unit = {
    val metrics =  MetricsReader.getNodeMetrics().metrics
    val filteredMetric = metrics
      .filter(m => Set("executor_tasks_submitted_total", "executor_tasks_completed_total").exists(m.name.contains) )
    println("filtered metrics: " + filteredMetric.map(m => s"${m.name}:${m.value}"))
  }
}