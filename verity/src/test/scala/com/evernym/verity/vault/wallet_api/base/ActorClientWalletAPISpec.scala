package com.evernym.verity.vault.wallet_api.base

import akka.actor.{ActorRef, Props}
import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.actor.agent.WalletVerKeyCacheHelper
import com.evernym.verity.actor.base.{CoreActor, Done}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.vault.WalletAPIParam
import com.evernym.verity.vault.wallet_api.WalletAPI
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Minutes, Seconds, Span}

trait ActorClientWalletAPISpecBase
  extends ClientWalletAPISpecBase
    with Eventually {

  lazy val walletSetupManager: ActorRef = system.actorOf(Props(new WalletSetupManager(appConfig, walletAPI)))

  override def startUserWalletSetupWithSyncAPI(): Unit = {
    walletSetupManager ! StartAgentCreation(totalUsers, useSyncWalletAPI = true)
    expectMsg(Done)
  }

  override def startUserWalletSetupWithAsyncAPI(): Unit = {
    walletSetupManager ! StartAgentCreation(totalUsers, useSyncWalletAPI = false)
    expectMsg(Done)
  }

  override def waitForAllResponses(): Unit =  {
    eventually(timeout(Span(10, Minutes)), interval(Span(10, Seconds))) {
      walletSetupManager ! GetStatus
      val status = expectMsgType[Status]
      successResp = status.successResp
      failedResp = status.failedResp
      println(s"[current-progress] success: $successResp, failed: $failedResp")
      failedResp shouldBe 0
      status.totalRespCount shouldBe totalUsers
    }
    println(s"[final-status] success: $successResp, failed: $failedResp")
    failedResp shouldBe 0
  }
}

//this is little bit like a region actor which receives a request
// and it creates actual mock agent actors
// and sends a command to those actors which starts wallet activity
class WalletSetupManager(appConfig: AppConfig, walletAPI: WalletAPI)
  extends CoreActor {

  var successResponse = 0
  var failedResponse = 0

  override def receiveCmd: Receive = {
    case sws: StartAgentCreation =>
      (1 to sws.totalUser).foreach { id =>
        val ar = context.actorOf(Props(new MockAgentActor(appConfig, walletAPI)), id.toString)
        ar ! StartWalletSetup(sws.useSyncWalletAPI)
      }
      sender ! Done

    case WalletSetupCompleted => successResponse += 1
    case WalletSetupFailed    => failedResponse += 1

    case GetStatus =>
      sender ! Status(successResponse, failedResponse)
  }
}

case class StartAgentCreation(totalUser: Int, useSyncWalletAPI: Boolean) extends ActorMessage
case object GetStatus extends ActorMessage
case class Status(successResp: Int, failedResp: Int) extends ActorMessage {
  def totalRespCount: Int = successResp + failedResp
}

//this is mocking agent actor which when receive 'StartWalletSetup' command
//it start exercises wallet sync apis
class MockAgentActor(appConfig: AppConfig, walletAPI: WalletAPI)
  extends CoreActor
    with UserWalletSetupHelper {

  import com.evernym.verity.ExecutionContextProvider.futureExecutionContext

  override def receiveCmd: Receive = {
    case StartWalletSetup(sync) => setupUser(sync)
  }

  implicit val wap: WalletAPIParam = WalletAPIParam(entityId)
  val walletVerKeyCacheHelper = new WalletVerKeyCacheHelper(wap, walletAPI, appConfig)

  /**
   * purpose of this method is to setup a new user wallet (and execute 3-4 wallet operations against it)
   **/
  def setupUser(useSyncWalletAPI: Boolean): Unit = {
    val userId = entityId.replace("WalletActor-", "").toInt
    if (useSyncWalletAPI) {
      try {
        _baseWalletSetupWithSyncAPI(userId, walletAPI)
        sender ! WalletSetupCompleted
      } catch {
        case e: RuntimeException =>
          e.printStackTrace()
          sender ! WalletSetupFailed

      }
    } else {
      val sndr = sender()
      _baseWalletSetupWithAsyncAPI(userId, walletAPI).map { _ =>
        sndr ! WalletSetupCompleted
      }.recover {
        case e: RuntimeException =>
          e.printStackTrace()
          sndr ! WalletSetupFailed
      }
    }
  }
}

case class StartWalletSetup(useSyncWalletAPI: Boolean) extends ActorMessage
case object WalletSetupCompleted extends ActorMessage
case object WalletSetupFailed extends ActorMessage