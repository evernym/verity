package com.evernym.verity.actor.agent.user

import java.time.ZonedDateTime
import com.evernym.verity.actor.{Evt, MsgCreated, MsgDeliveryStatusUpdated, MsgDetailAdded, MsgPayloadStored}
import com.evernym.verity.actor.agent.MsgAndDelivery
import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil._
import com.evernym.verity.util2.Status._
import com.evernym.verity.actor.agent.user.msgstore.{MsgStateAPIProvider, MsgStore}
import com.evernym.verity.agentmsg.msgfamily.pairwise.GetMsgsReqMsg
import com.evernym.verity.did.didcomm.v1.messages.MsgId
import com.evernym.verity.observability.metrics.CustomMetrics._
import com.evernym.verity.observability.metrics.{MetricsWriter, TestMetricsBackend}
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.util.MsgIdProvider
import com.google.protobuf.ByteString
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}


class MsgStoreSpec
  extends BasicSpec
    with BeforeAndAfterEach
    with Eventually {

  private val testMetricsBackend = new TestMetricsBackend
  val testMetricsWriter = new MetricsWriter(testMetricsBackend)

  override protected def beforeEach(): Unit = {
    testMetricsBackend.reset()
    testMetricsBackend.allHistogramMetrics().size shouldBe 0
    super.beforeEach()
  }

  "MgsStore" - {

    "when asked to build an empty msg store" - {
      "and queried back its state" - {
        "should respond with no messages" in {
          val config = createConfig(enabled = true, isDeliveryAckRequired = true, 10, 10)
          val msgStore = buildMsgStore(config)
          msgStore.getMsgs(GetMsgsReqMsg(None)).size shouldBe 0
        }
      }
    }

    "when asked to build non empty msg store" - {
      "with 'message state cleanup' config disabled" - {
        "and queried back its state" - {
          "should respond with all messages" in {
            val config = createConfig(enabled = false, isDeliveryAckRequired = false, 10, 10)
            val msgStore = buildMsgStore(config, 5, 10, 15)
            msgStore.getMsgs(GetMsgsReqMsg(None)).size shouldBe 30
          }
        }
      }
    }

    "when asked to build non empty msg store" - {
      "with config which allows to keep all those messages" - {
        "and queried back its state" - {
          "should respond with all messages" in {
            val config = createConfig(enabled = true, isDeliveryAckRequired = true,10, 30)
            val msgStore = buildMsgStore(config, 5, 10, 15)
            msgStore.getMsgs(GetMsgsReqMsg(None)).size shouldBe 30
            checkMessageCleanupMetrics(msgStore, 30, 0)
          }
        }
      }
    }

    "when asked to build non empty msg store" - {
      "with delivery ack required config" - {
        "and queried back its state" - {
          "should respond with expected results" in {
            val config = createConfig(enabled = true, isDeliveryAckRequired = true, 2, 20)
            val msgStore = buildMsgStore(config, 5, 10, 15)
            val msgs = msgStore.getMsgs(GetMsgsReqMsg(None))
            msgs.size shouldBe 20
            checkMessageCleanupMetrics(msgStore, 20, 10)
          }
        }
      }
    }

    "when asked to build non empty msg store" - {
      "with delivery ack NOT required config" - {
        "and queried back its state" - {
          "should respond with expected results" in {
            val config = createConfig(enabled = true, isDeliveryAckRequired = false, 2, 5)
            val msgStore = buildMsgStore(config, 5, 10, 15)
            val msgs = msgStore.getMsgs(GetMsgsReqMsg(None))
            msgs.size shouldBe 5
            checkMessageCleanupMetrics(msgStore, 5, 25)
          }
        }
      }
    }

    "when asked to build non empty msg store" - {
      "with config which allows to clear few of those messages" - {
        "and queried back its state" - {
          "should respond with expected results" in {
            val config = createConfig(enabled = true, isDeliveryAckRequired = true, 2, 12)
            val msgStore = buildMsgStore(config, 5, 10, 15)
            val msgs = msgStore.getMsgs(GetMsgsReqMsg(None))
            msgs.size shouldBe 12
            checkMessageCleanupMetrics(msgStore, 12, 18)
          }
        }
      }
    }

    "when asked to build msg store with only delivered messages" - {
      "with config which allows to clear few of those messages" - {
        "and queried back its state" - {
          "should respond with expected results" in {
            val config = createConfig(enabled = true, isDeliveryAckRequired = false, 5, 10)
            val msgStore = buildMsgStore(config, 5, 10)
            val msgs = msgStore.getMsgs(GetMsgsReqMsg(None))
            msgs.size shouldBe 10
            checkMessageCleanupMetrics(msgStore, 10, 5)
          }
        }
      }
    }

    "when asked to build msg store with only undelivered messages" - {
      "with config which allows to clear few of those messages" - {
        "and queried back its state" - {
          "should respond with expected results" in {
            val config = createConfig(enabled = true, isDeliveryAckRequired = true, 5, 10)
            val msgStore = buildMsgStore(config, 0, 5, 10)
            val msgs = msgStore.getMsgs(GetMsgsReqMsg(None))
            msgs.size shouldBe 10
            checkMessageCleanupMetrics(msgStore, 10, 5)
          }
        }
      }
    }

    "when asked to build msg store with mixed delivery status" - {
      "with config which allows to clear few of those messages" - {
        "and queried back its state" - {
          "should respond with expected results" in {
            val config = createConfig(enabled = true, isDeliveryAckRequired = false, 5, 30)
            val msgStore = buildMsgStore(config, 5, 10, 15)
            val msgs = msgStore.getMsgs(GetMsgsReqMsg(None))
            msgs.size shouldBe 25
            checkMessageCleanupMetrics(msgStore, 25, 5)
          }
        }
      }
    }

    "when asked to build msg store with large message sets" - {
      "with config which allows to clear few of those messages" - {
        "and queried back its state" - {
          "should respond with expected results" in {
            val config = createConfig(enabled = true, isDeliveryAckRequired = false, 5, 150)
            val msgStore = buildMsgStore(config, 50, 100, 200)
            val msgs = msgStore.getMsgs(GetMsgsReqMsg(None))
            msgs.size shouldBe 150
            checkMessageCleanupMetrics(msgStore, 150, 200)
          }
        }
      }
    }

    "when asked to build msg store with some large messages" - {
      "with config that allows to omit some of those messages on return" - {
        "and queried back its state" - {
          "should respond with expected results" in {
            val config = createConfig(enabled = true, isDeliveryAckRequired = false, 5, 150, getMsgsLimit = 190)
            val message = ByteString.copyFromUtf8("12345678901234567890")
            val msgStore = buildMsgStore(config, 0, 0, 10, message)
            val msgs = msgStore.getMsgs(GetMsgsReqMsg(None))
            msgs.size shouldBe 9
          }
        }
      }
    }

  }


  def checkMessageCleanupMetrics(msgStore: MsgStore,
                                 expectedRetainedMsgsSum: Int,
                                 expectedRemovedMsgsSum: Int): Unit = {
    msgStore.updateMsgStateMetrics()

    eventually(timeout(Span(10, Seconds)), interval(Span(200, Millis))) {

      val retainedMsgsSumMetrics = testMetricsBackend.filterHistogramMetrics(s"${AS_AKKA_ACTOR_AGENT_RETAINED_MSGS}")
      val removedMsgsSumMetrics = testMetricsBackend.filterHistogramMetrics(s"${AS_AKKA_ACTOR_AGENT_REMOVED_MSGS}")
      val totalActorWithRemovedMsgMetrics = testMetricsBackend.filterHistogramMetrics(s"${AS_AKKA_ACTOR_AGENT_WITH_MSGS_REMOVED}")

      if (expectedRemovedMsgsSum > 0) {
        retainedMsgsSumMetrics.size shouldBe 1
        retainedMsgsSumMetrics.head._2.sum shouldBe expectedRetainedMsgsSum

        removedMsgsSumMetrics.size shouldBe 1
        removedMsgsSumMetrics.head._2.sum shouldBe expectedRemovedMsgsSum

        totalActorWithRemovedMsgMetrics.size shouldBe 1
      }
    }
  }

  def buildMsgStore(config: Config,
                    noOfDeliveredAckMsgs: Int = 0,
                    noOfDeliveredNonAckMsgs: Int = 0,
                    noOfUnDeliveredMsgs: Int = 0,
                    message: ByteString = ByteString.EMPTY): MsgStore = {
    val msgStore = new MsgStore(new TestAppConfig(Option(config)), new MockMsgStateAPIProvider, None, testMetricsWriter)
    addDeliveredAckMsgs(config, msgStore, noOfDeliveredAckMsgs)
    addDeliveredNonAckMsgs(config, msgStore, noOfDeliveredNonAckMsgs)
    addUndeliveredMsgs(config, msgStore, noOfUnDeliveredMsgs, message)
    msgStore
  }

  def addDeliveredNonAckMsgs(config: Config, msgStore: MsgStore, count: Int): Unit = {
    (1 to count).reverse.foreach { i =>
      val mc = buildMsgCreated(i, MSG_STATUS_ACCEPTED)
      val md = buildMsgDeliveryDetail(i, mc.uid, "destination", MSG_DELIVERY_STATUS_SENT)
      addMsgAndDeliveryStatus(config, msgStore, mc, List(md))
    }
  }

  def addDeliveredAckMsgs(config: Config, msgStore: MsgStore, count: Int): Unit = {
    (1 to count).reverse.foreach { i =>
      val mc = buildMsgCreated(i, MSG_STATUS_REVIEWED)
      val mds = List(
        buildMsgDeliveryDetail(i, mc.uid, "destination", MSG_DELIVERY_STATUS_PENDING),
        buildMsgDeliveryDetail(i, mc.uid, "destination", MSG_DELIVERY_STATUS_SENT)
      )
      addMsgAndDeliveryStatus(config, msgStore, mc, mds)
    }
  }

  def addUndeliveredMsgs(config: Config,
                         msgStore: MsgStore,
                         count: Int,
                         message: ByteString = ByteString.EMPTY): Unit = {
    (1 to count).reverse.foreach { i =>
      val mc = buildMsgCreated(i, MSG_STATUS_CREATED)
      val md =
        if (i % 2 == 0) List(buildMsgDeliveryDetail(i, mc.uid, "destination", MSG_DELIVERY_STATUS_PENDING))
        else if (i % 3 ==0) List(buildMsgDeliveryDetail(i, mc.uid, "destination", MSG_DELIVERY_STATUS_FAILED))
        else List.empty
      addMsgAndDeliveryStatus(config, msgStore, mc, md, message)
    }
  }

  def addMsgAndDeliveryStatus(config: Config,
                              msgStore: MsgStore,
                              mc: MsgCreated,
                              md: List[MsgDeliveryStatusUpdated],
                              message: ByteString = ByteString.EMPTY): Unit = {
    val isCleanupEnabled = config.getBoolean("verity.agent.state.messages.cleanup.enabled")
    val maxAllowedMsgCount = config.getInt("verity.agent.state.messages.cleanup.total-msgs-to-retain")
    msgStore.handleMsgCreated(mc)
    msgStore.handleMsgDetailAdded(MsgDetailAdded(mc.uid, "name", "Enterprise"))
    msgStore.handleMsgPayloadStored(MsgPayloadStored(mc.uid, message))
    if (isCleanupEnabled) msgStore.getMsgs(GetMsgsReqMsg(None)).size <= maxAllowedMsgCount
    md.foreach(msgStore.handleMsgDeliveryStatusUpdated)
    if (isCleanupEnabled) msgStore.getMsgs(GetMsgsReqMsg(None)).size <= maxAllowedMsgCount
  }

  def buildMsgCreated(index: Int, statusDetail: StatusDetail): MsgCreated = {
    MsgCreated(MsgIdProvider.getNewMsgId,
      CREATE_MSG_TYPE_CONN_REQ,
      "senderDID",
      statusDetail.statusCode,
      ZonedDateTime.now().minusDays(index).toInstant.toEpochMilli,
      ZonedDateTime.now().minusDays(index).toInstant.toEpochMilli,
      Evt.defaultUnknownValueForStringType, None)
  }

  def buildMsgDeliveryDetail(index: Int,
                             msgId: MsgId,
                             destination: String,
                             statusDetail: StatusDetail): MsgDeliveryStatusUpdated = {
    MsgDeliveryStatusUpdated(
      msgId,
      destination,
      statusDetail.statusCode,
      "delivered",
      ZonedDateTime.now().minusDays(index).toInstant.toEpochMilli
    )
  }

  def createConfig(enabled: Boolean,
                   isDeliveryAckRequired: Boolean,
                   daysToRetainDeliveredMsgs: Int,
                   totalMsgsToRetain: Int,
                   getMsgsLimit: Int = 920000): Config = {

    val enabledStr = if (enabled) "true" else "false"
    val deliveryAckConfig = if (isDeliveryAckRequired) {
      """akka.sharding-region-name.user-agent = "UserAgent""""
    } else {
      """akka.sharding-region-name.user-agent = "VerityAgent""""
    }
    ConfigFactory.parseString {
      s"""verity.agent.state.messages {
          get-msgs.limit = $getMsgsLimit
          cleanup {
            enabled = $enabledStr
            days-to-retain-delivered-msgs = $daysToRetainDeliveredMsgs
            total-msgs-to-retain = $totalMsgsToRetain
           }
         }
         $deliveryAckConfig
         """
    }.withFallback(ConfigFactory.load())
  }
}

class MockMsgStateAPIProvider extends MsgStateAPIProvider {

  var _msgAndDelivery = new MsgAndDelivery()

  override def msgAndDelivery: Option[MsgAndDelivery] = Option(_msgAndDelivery)
  override def updateMsgAndDelivery(msgAndDelivery: MsgAndDelivery): Unit =
    _msgAndDelivery = msgAndDelivery
}