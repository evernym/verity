package com.evernym.verity.msgoutbox.outbox_router

import akka.actor.typed.ActorRef
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.pattern.StatusReply
import com.evernym.verity.actor.agent.relationship.KeyId
import com.evernym.verity.actor.typed.EventSourcedBehaviourSpecBase
import com.evernym.verity.msgoutbox.base.BaseMsgOutboxSpec
import com.evernym.verity.msgoutbox.message_meta.MessageMeta
import com.evernym.verity.msgoutbox.message_meta.MessageMeta.Commands
import com.evernym.verity.msgoutbox.message_meta.MessageMeta.Replies.MsgDeliveryStatus
import com.evernym.verity.msgoutbox.outbox.Outbox
import com.evernym.verity.msgoutbox.outbox.Outbox.Commands.GetDeliveryStatus
import com.evernym.verity.msgoutbox.outbox.Outbox.Replies
import com.evernym.verity.msgoutbox.router.OutboxRouter
import com.evernym.verity.msgoutbox.{DID, OutboxId, ParticipantId, RecipId, RelId}
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.util.ParticipantUtil
import com.evernym.verity.util2.Status
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}


trait OutboxRouterSpecBase
  extends EventSourcedBehaviourSpecBase
    with BaseMsgOutboxSpec
    with BasicSpec
    with Eventually {

  def sendToOutboxRouter(fromParticipantId: ParticipantId,
                         toParticipantId: ParticipantId,
                         msg: String,
                         msgType: String): OutboxRouter.Replies.Ack = {
    val relId = ParticipantUtil.DID(fromParticipantId)
    val recipId = ParticipantUtil.agentId(toParticipantId)
    _sendToOutboxRouter(relId, recipId, msg, msgType)
  }

  private def _sendToOutboxRouter(relId: RelId,
                                  recipId: RecipId,
                                  msg: String,
                                  msgType: String): OutboxRouter.Replies.Ack = {
    val testProbe = createTestProbe[OutboxRouter.Reply]
    val router = spawn(
      OutboxRouter(
        relId,
        recipId,
        msg,
        msgType,
        retentionPolicy,
        testRelResolver,
        testMsgStore,
        Option(testProbe.ref)
      )
    )
    router ! OutboxRouter.Commands.SendMsg
    testProbe.expectMessageType[OutboxRouter.Replies.Ack]
  }

  protected def checkOutboxProcessing(ack: OutboxRouter.Replies.Ack,
                                      expectedTargetOutboxIds: Seq[OutboxId]): Unit = {
    ack.targetOutboxIds shouldBe expectedTargetOutboxIds

    val outboxProbe = createTestProbe[StatusReply[Outbox.Replies.DeliveryStatus]]()
    val messageMetaProbe = createTestProbe[StatusReply[MessageMeta.Replies.MsgDeliveryStatus]]()

    //below code block checks that:
    // * a message got stored in external storage (s3)
    // * its message meta actor has its delivery information and
    // * it also got added to given outbox
    storageAPI.getBlobObjectCount("20", BUCKET_NAME) shouldBe 1

    messageMetaRegion ! ShardingEnvelope(ack.msgId, Commands.GetDeliveryStatus(messageMetaProbe.ref))
    val msgDeliveryStatus = messageMetaProbe.expectMessageType[StatusReply[MsgDeliveryStatus]].getValue
    msgDeliveryStatus.outboxDeliveryStatus.size shouldBe 1

    ack.targetOutboxIds.foreach { outboxId =>
      outboxRegion ! ShardingEnvelope(outboxId, GetDeliveryStatus(outboxProbe.ref))
      val messages = outboxProbe.expectMessageType[StatusReply[Replies.DeliveryStatus]].getValue.messages
      messages.size shouldBe 1
    }

    //checking that
    // * the message is now DELETED from external storage (s3)
    // * it is also DELETED from the outbox (because it got delivered now) and
    // * its meta data is STILL stored in message meta actor
    eventually(timeout(Span(5, Seconds)), interval(Span(100, Millis))) {
      storageAPI.getBlobObjectCount("20", BUCKET_NAME) shouldBe 0

      messageMetaRegion ! ShardingEnvelope(ack.msgId, Commands.GetDeliveryStatus(messageMetaProbe.ref))
      val msgDeliveryStatus = messageMetaProbe.expectMessageType[StatusReply[MsgDeliveryStatus]].getValue
      msgDeliveryStatus.outboxDeliveryStatus.size shouldBe 1
      msgDeliveryStatus.outboxDeliveryStatus.values.forall(_.status == Status.MSG_DELIVERY_STATUS_SENT.statusCode) shouldBe true

      ack.targetOutboxIds.foreach { outboxId =>
        outboxRegion ! ShardingEnvelope(outboxId, GetDeliveryStatus(outboxProbe.ref))
        val messages = outboxProbe.expectMessageType[StatusReply[Replies.DeliveryStatus]].getValue.messages
        messages.size shouldBe 0
      }
    }
  }

  val SNAPSHOT_CONFIG: Config = ConfigFactory.parseString {
    """
      |verity.outbox.retention-criteria.snapshot.after-every-events = 1
      |verity.outbox.retention-criteria.snapshot.keep-snapshots = 1
      |verity.outbox.retention-criteria.snapshot.delete-events-on-snapshots = true
      |""".stripMargin
  }

  def outboxRegion: ActorRef[ShardingEnvelope[Outbox.Cmd]]
}

trait AgentContext {    //represents either VerityEdgeAgent or VerityCloudAgent
  def selfRelContext: SelfRelContext
  def pairwiseRelContext: PairwiseRelContext
}

trait SelfRelContext {
  def myDID: DID
  def thisAgentKeyId: KeyId

  def selfParticipantId: ParticipantId      //represents thisAgentKeyId participant-id
  def myDomainParticipantId: ParticipantId  //represents myDID participant-id

  def otherParticipantId: ParticipantId     //represents myDID participant-id
}

trait PairwiseRelContext {
  def selfRelDID: DID
  def thisAgentKeyId: KeyId

  def myPairwiseDID: DID
  def selfParticipantId: ParticipantId      //represents myPairwiseDID participant-id
  def myDomainParticipantId: ParticipantId  //represents selfRelDID participant-id

  def theirPairwiseDID: DID
  def otherParticipantId: ParticipantId     //represents theirPairwiseDID participant-id
}