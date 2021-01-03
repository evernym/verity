package com.evernym.integrationtests.e2e.third_party_apis.wallet_api

import java.io.File
import java.util.UUID

import akka.actor.Props
import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.actor.agent.WalletVerKeyCacheHelper
import com.evernym.verity.actor.base.{CoreActor, Done}
import com.evernym.verity.actor.testkit.{ActorSpec, CommonSpecUtil}
import com.evernym.verity.actor.wallet._
import com.evernym.verity.actor.testkit.actor.ProvidesMockPlatform
import com.evernym.verity.config.AppConfig
import com.evernym.verity.metrics.MetricsReader
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.vault.WalletAPIParam
import com.evernym.verity.vault.wallet_api.WalletAPI
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Minutes, Seconds, Span}


/**
 * this is an integration test and it depends on mysql based wallet storage
 * mysql can be installed by executing        : <repo-root>/devops/scripts/wallet-storage-mysql/install.sh
 * required tables can be setup by executing  : <repo-root>/devops/scripts/wallet-storage-mysql/clean-setup.sh
 */

//NOTE: this one exercises "SYNC wallet api" from within actors
class WalletAPIPerformanceSpec
  extends ActorSpec
    with ProvidesMockPlatform
    with BasicSpec
    with Eventually {

  MetricsReader

  var totalUser: Int = 1000

  lazy val walletSetupManager = system.actorOf(Props(new WalletSetupManager(appConfig, walletAPI)))

  "WalletService" - {
    "when tried to setup lots of user wallets parallely" - {
      "should be successful" in {
        walletSetupManager ! StartWalletSetup(totalUser)
        expectMsg(Done)

        eventually(timeout(Span(10, Minutes)), interval(Span(10, Seconds))) {
          walletSetupManager ! GetStatus
          val status = expectMsgType[Status]
          status.successResp shouldBe totalUser
        }
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

class WalletSetupManager(appConfig: AppConfig, walletAPI: WalletAPI)
  extends CoreActor {

  var successResponse = 0

  override def receiveCmd: Receive = {
    case sws: StartWalletSetup =>
      (1 to sws.totalUser).foreach { _ =>
        val ar = context.actorOf(Props(new MockAgentActor(appConfig, walletAPI)), UUID.randomUUID().toString)
        ar ! StartWalletSetup
      }
      sender ! Done

    case WalletSetupCompleted => successResponse += 1

    case GetStatus =>
      sender ! Status(successResponse)
  }
}

case class StartWalletSetup(totalUser: Int) extends ActorMessage
case object GetStatus extends ActorMessage
case class Status(successResp: Int) extends ActorMessage

class MockAgentActor(appConfig: AppConfig, walletAPI: WalletAPI)
  extends CoreActor {

  override def receiveCmd: Receive = {
    case StartWalletSetup => setupUser()
  }

  implicit val wap: WalletAPIParam = WalletAPIParam(entityId)
  val walletVerKeyCacheHelper = new WalletVerKeyCacheHelper(wap, walletAPI, appConfig)

  /**
   * purpose of this method is to setup a new user wallet (and execute 3-4 wallet operations against it)
   **/
  def setupUser(): Unit = {
    val ddPair = CommonSpecUtil.generateNewDid()
    println(s"[$entityId] about to start executing wallet operations for an user")
    val wc = walletAPI.createWallet(wap)
    println(s"[$entityId] wallet created")
    val nkc = walletAPI.createNewKey(CreateNewKey())
    println(s"[$entityId] new key created")
    val stk = walletAPI.storeTheirKey(StoreTheirKey(ddPair.DID, ddPair.verKey))
    println(s"[$entityId] their key stored")
    walletVerKeyCacheHelper.getVerKeyReqViaCache(nkc.did)
    println(s"[$entityId] ver key retrieved")
    sender ! WalletSetupCompleted
  }
}

case object StartWalletSetup extends ActorMessage
case object WalletSetupCompleted extends ActorMessage