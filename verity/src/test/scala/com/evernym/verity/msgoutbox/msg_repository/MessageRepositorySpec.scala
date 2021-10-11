package com.evernym.verity.msgoutbox.msg_repository

import com.evernym.verity.actor.typed.BehaviourSpecBase
import com.evernym.verity.msgoutbox.MsgId
import com.evernym.verity.msgoutbox.Msg
import com.evernym.verity.msgoutbox.base.BaseMsgOutboxSpec
import com.evernym.verity.msgoutbox.outbox.OutboxIdParam
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.util.TestExecutionContextProvider
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

class MessageRepositorySpec
  extends BehaviourSpecBase
    with BaseMsgOutboxSpec
    with BasicSpec
    with Eventually {
  var id = ""

  "can store and read message" in {
    val insertFuture: Future[MsgId] = testMsgRepository.insert(
      "test-type",
      "test-message",
      retentionPolicy,
      Set(OutboxIdParam("id1", "id1", "id1"))
    )

    eventually(timeout(Span(10, Seconds)), interval(Span(100, Millis))) {
      insertFuture.value match {
        case Some(Success(insertedId)) => id = insertedId
        case Some(Failure(e)) => fail(e)
        case None => fail("not completed")
      }
    }

    val readNoPayloadFuture: Future[List[Msg]] = testMsgRepository.read(List(id), true)

    eventually(timeout(Span(10, Seconds)), interval(Span(100, Millis))) {
      readNoPayloadFuture.value match {
        case Some(Success(list)) =>
          list.size shouldBe 1
          val msg = list.head
          msg.id shouldBe id
          msg.payload shouldBe empty
        case Some(Failure(e)) => fail(e)
        case None => fail("not completed")
      }
    }

    val readPayloadFuture: Future[List[Msg]] = testMsgRepository.read(List(id), false)

    eventually(timeout(Span(10, Seconds)), interval(Span(100, Millis))) {
      readPayloadFuture.value match {
        case Some(Success(list)) =>
          list.size shouldBe 1
          val msg = list.head
          msg.id shouldBe id
          msg.payload should not be empty
        case Some(Failure(e)) => fail(e)
        case None => fail("not completed")
      }
    }
  }

  override def futureExecutionContext: ExecutionContext = TestExecutionContextProvider.ecp.futureExecutionContext
}