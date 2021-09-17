package com.evernym.verity.msgoutbox

import java.util.UUID

import com.evernym.verity.actor.typed.BehaviourSpecBase
import com.evernym.verity.app_launcher.DefaultAgentActorContext
import com.evernym.verity.msgoutbox.base.BaseMsgOutboxSpec
import com.evernym.verity.actor.agent.user.msgstore.MsgDetail
import com.evernym.verity.msgoutbox.outbox.OutboxIdParam
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.util.TestExecutionContextProvider
import com.evernym.verity.util2.RetentionPolicy
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

class OutboxServiceSpec
  extends BehaviourSpecBase
    with BaseMsgOutboxSpec
    with BasicSpec
    with Eventually {

  var messageMap: Map[String, (RetentionPolicy, String, Array[Byte])] = Map()

  override val testMsgRepository = new MessageRepository {
    override def insert(msgType: String, msg: String, retentionPolicy: RetentionPolicy, outboxParams: Set[OutboxIdParam]): Future[MsgId] = {
      val id = UUID.randomUUID().toString
      messageMap = messageMap + (id -> (retentionPolicy, msgType, msg.getBytes()))
      Future.successful(id)
    }
    override def read(ids: List[MsgId], excludePayload: Boolean): Future[List[Msg]] = {
      Future.successful(
        messageMap
          .filter( p => ids.contains(p._1))
          .map(p => Msg(
            p._1,
            p._2._2,
            None,
            if (excludePayload) None else Some(p._2._3),
            p._2._1,
            None
          )).toList
      )
    }
  }

  val outboxService = OutboxService(
    testRelResolver,
    testMsgRepository,
    testMsgPackagers,
    new DefaultAgentActorContext(TestExecutionContextProvider.ecp, appConfig),
    appConfig
  )(executionContext, system)

  "when sending a message" - {
    "it should be sent and read successfully" in {
      var msgId = "";
      val sendFuture: Future[String] = outboxService.sendMessage(
        Map("id1" -> "id1"),
        "test message",
        "test-message",
        retentionPolicy
      )
      eventually(timeout(Span(10, Seconds)), interval(Span(100, Millis))) {
        sendFuture.value match {
          case Some(Success(id)) =>
            id should not be empty
            msgId = id
          case Some(Failure(e)) => fail(e)
          case None => fail("not completed")
        }
      }

      messageMap should contain key msgId

      val getFuture: Future[Seq[MsgDetail]] = outboxService.getMessages("id1", "id1", List(), List(), true)
      eventually(timeout(Span(10, Seconds)), interval(Span(100, Millis))) {
        getFuture.value match {
          case Some(Success(messages)) =>
            messages.size shouldBe 1
            val msg = messages.head
            msg.payload shouldBe empty
          case Some(Failure(e)) => fail(e)
          case None => fail("not completed")
        }
      }

      val getPayloadFuture: Future[Seq[MsgDetail]] = outboxService.getMessages("id1", "id1", List(), List(), false)
      eventually(timeout(Span(10, Seconds)), interval(Span(100, Millis))) {
        getPayloadFuture.value match {
          case Some(Success(messages)) =>
            messages.size shouldBe 1
            val msg = messages.head
            msg.payload should not be empty
          case Some(Failure(e)) => fail(e)
          case None => fail("not completed")
        }
      }

      val sendSecondFuture: Future[String] = outboxService.sendMessage(
        Map("id1" -> "id1"),
        "test message-2",
        "test-message-2",
        retentionPolicy
      )

      val getIdFilterFuture = sendSecondFuture.flatMap(id => outboxService.getMessages("id1", "id1", List(id), List(), true).map(res => (res, id)))
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

  override def futureExecutionContext: ExecutionContext = TestExecutionContextProvider.ecp.futureExecutionContext

  override def futureWalletExecutionContext: ExecutionContext = TestExecutionContextProvider.ecp.walletFutureExecutionContext
}