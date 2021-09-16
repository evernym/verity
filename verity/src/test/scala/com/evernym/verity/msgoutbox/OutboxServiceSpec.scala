package com.evernym.verity.msgoutbox

import com.evernym.verity.actor.typed.BehaviourSpecBase
import com.evernym.verity.app_launcher.DefaultAgentActorContext
import com.evernym.verity.msgoutbox.base.BaseMsgOutboxSpec
import com.evernym.verity.actor.agent.user.msgstore.MsgDetail
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
          case None => fail("not completed")
        }
      }
    }

    "it should be read successfully" - {
      "without payload" in {
        val getFuture: Future[Seq[MsgDetail]] = outboxService.getMessages("id1", "id1", List(), List(), true)
        eventually(timeout(Span(10, Seconds)), interval(Span(100, Millis))) {
          getFuture.value match {
            case Some(Success(messages)) =>
              messages.size shouldBe 1
              val msg = messages.head
              msg.payload.nonEmpty shouldBe false
            case Some(Failure(e)) => fail(e)
            case None => fail("not completed")
          }
        }
      }

      "with payload" in {
        val getFuture: Future[Seq[MsgDetail]] = outboxService.getMessages("id1", "id1", List(), List(), false)
        eventually(timeout(Span(10, Seconds)), interval(Span(100, Millis))) {
          getFuture.value match {
            case Some(Success(messages)) =>
              messages.size shouldBe 1
              val msg = messages.head
              msg.payload.nonEmpty shouldBe true
            case Some(Failure(e)) => fail(e)
            case None => fail("not completed")
          }
        }
      }

      "with id filter" in {
        val sendFuture: Future[String] = outboxService.sendMessage(
          Map("id1" -> "id1"),
          "test message-2",
          "test-message-2",
          retentionPolicy
        )

        val getIdFilterFuture = sendFuture.flatMap(id => outboxService.getMessages("id1", "id1", List(id), List(), true).map(res => (res, id)))
        eventually(timeout(Span(10, Seconds)), interval(Span(100, Millis))) {
          getIdFilterFuture.value match {
            case Some(Success((messages, id))) =>
              messages.size shouldBe 1
              val msg = messages.head
              msg.uid shouldBe id
            case Some(Failure(e)) => fail(e)
            case None => fail("not completed")
          }
        }
      }

      "with status filter" in {

        val getAllFuture = outboxService.getMessages("id1", "id1", List(), List("MDS-101"), true)
        eventually(timeout(Span(10, Seconds)), interval(Span(100, Millis))) {
          getAllFuture.value match {
            case Some(Success(messages)) =>
              messages.size shouldBe 2
            case Some(Failure(e)) => fail(e)
            case None => fail("not completed")
          }
        }
        val getNoneFuture = outboxService.getMessages("id1", "id1", List(), List("MDS-1012"), true)
        eventually(timeout(Span(10, Seconds)), interval(Span(100, Millis))) {
          getNoneFuture.value match {
            case Some(Success(messages)) =>
              messages.size shouldBe 0
            case Some(Failure(e)) => fail(e)
            case None => fail("not completed")
          }
        }
      }
    }
  }

  override def futureExecutionContext: ExecutionContext = TestExecutionContextProvider.ecp.futureExecutionContext

  override def futureWalletExecutionContext: ExecutionContext = TestExecutionContextProvider.ecp.walletFutureExecutionContext
}