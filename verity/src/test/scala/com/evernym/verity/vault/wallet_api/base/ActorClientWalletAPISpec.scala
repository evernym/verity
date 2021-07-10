package com.evernym.verity.vault.wallet_api.base

import akka.actor.{ActorRef, Props}
import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.actor.base.{CoreActor, Done}
import com.evernym.verity.vault.WalletAPIParam
import com.evernym.verity.vault.wallet_api.WalletAPI
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Minutes, Span}

trait ActorClientWalletAPISpecBase
  extends ClientWalletAPISpecBase
    with Eventually {

  lazy val walletSetupManager: ActorRef = system.actorOf(Props(new WalletSetupManager(walletAPI)))

  override def startUserWalletSetupWithAsyncAPI(): Unit = {
    walletSetupManager ! StartAgentCreation(totalUsers)
    expectMsg(Done)
  }

  override def waitForAllResponses(): Unit =  {
    eventually(timeout(Span(10, Minutes)), interval(Span(100, Millis))) {
      walletSetupManager ! GetStatus
      val status = expectMsgType[Status]
      successResp.set(status.successResp)
      failedResp.set(status.failedResp)
      status.totalRespCount shouldBe totalUsers
    }
    failedResp.get() shouldBe 0
  }
}

//this is little bit like a region actor which receives a request
// and it creates actual mock agent actors
// and sends a command to those actors which starts wallet activity
class WalletSetupManager(walletAPI: WalletAPI)
  extends CoreActor {

  var successResponse = 0
  var failedResponse = 0

  override def receiveCmd: Receive = {
    case sws: StartAgentCreation =>
      (1 to sws.totalUser).foreach { id =>
        val ar = context.actorOf(Props(new MockAgentActor(walletAPI)), id.toString)
        ar ! StartWalletSetup()
      }
      sender ! Done

    case WalletSetupCompleted => successResponse += 1
    case WalletSetupFailed    => failedResponse += 1

    case GetStatus =>
      sender ! Status(successResponse, failedResponse)
  }
}

case class StartAgentCreation(totalUser: Int) extends ActorMessage
case object GetStatus extends ActorMessage
case class Status(successResp: Int, failedResp: Int) extends ActorMessage {
  def totalRespCount: Int = successResp + failedResp
}

//this is mocking agent actor which when receive 'StartWalletSetup' command
//it start exercises wallet sync apis
class MockAgentActor(walletAPI: WalletAPI)
  extends CoreActor
    with UserWalletSetupHelper {

  import com.evernym.verity.util2.ExecutionContextProvider.futureExecutionContext

  override def receiveCmd: Receive = {
    case _: StartWalletSetup => setupUser()
  }

  implicit val wap: WalletAPIParam = WalletAPIParam(entityId)

  /**
   * purpose of this method is to setup a new user wallet (and execute 3-4 wallet operations against it)
   **/
  def setupUser(): Unit = {
    val sndr = sender()
    _baseWalletSetupWithAsyncAPI(walletAPI).map { _ =>
      sndr ! WalletSetupCompleted
    }.recover {
      case e: RuntimeException =>
        e.printStackTrace()
        sndr ! WalletSetupFailed
    }
  }
}

case class StartWalletSetup() extends ActorMessage
case object WalletSetupCompleted extends ActorMessage
case object WalletSetupFailed extends ActorMessage