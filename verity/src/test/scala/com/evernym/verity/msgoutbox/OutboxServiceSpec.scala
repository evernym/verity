package com.evernym.verity.msgoutbox

import com.evernym.verity.actor.typed.BehaviourSpecBase
import com.evernym.verity.app_launcher.DefaultAgentActorContext
import com.evernym.verity.msgoutbox.base.BaseMsgOutboxSpec
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.util.TestExecutionContextProvider
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

class OutboxServiceSpec
  extends BehaviourSpecBase
    with BaseMsgOutboxSpec
    with BasicSpec
    with Eventually {
  val outboxService = new OutboxService(
    testMsgStore,
    testRelResolver,
    testMsgRepository,
    testMsgPackagers,
    new DefaultAgentActorContext(TestExecutionContextProvider.ecp, appConfig),
    appConfig
  )(executionContext, system)

  "when sending a message" - {
    "it should be sent successfully" in {
      val sendFuture: Future[String] = outboxService.sendMessage(
        Map("id1" -> "id1"),
        "test message",
        "test-message",
        retentionPolicy
      )
      eventually(timeout(Span(10, Seconds)), interval(Span(100, Millis))) {
        sendFuture.value match {
          case Some(Success(id)) => id.nonEmpty shouldBe true
          case Some(Failure(e)) => fail(e)
          case None => fail("not complemeted")
        }
      }
    }

    "it should be read successfully" in {
    }
  }

  override def futureExecutionContext: ExecutionContext = TestExecutionContextProvider.ecp.futureExecutionContext

  override def futureWalletExecutionContext: ExecutionContext = TestExecutionContextProvider.ecp.walletFutureExecutionContext
}