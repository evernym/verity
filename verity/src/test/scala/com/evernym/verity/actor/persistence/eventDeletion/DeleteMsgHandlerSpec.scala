package com.evernym.verity.actor.persistence.eventDeletion

import akka.actor.{ActorLogging, ActorRef, Props}
import akka.persistence.{DeleteMessagesFailure, DeleteMessagesSuccess}
import akka.testkit.EventFilter
import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.actor.base.Done
import com.evernym.verity.actor.{ActorMessage, ConfigUpdated, TestJournal}
import com.evernym.verity.actor.persistence.{BasePersistentActor, DefaultPersistenceEncryption, GetPersistentActorDetail, PersistentActorDetail}
import com.evernym.verity.actor.testkit.{ActorSpec, AkkaTestBasic}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.util.TimeZoneUtil.{getCurrentUTCZonedDateTime, getMillisFromZonedDateTime}
import com.typesafe.config.{Config, ConfigFactory}

import scala.concurrent.{ExecutionContext, Future}

//tests how deletion of events (messages) handled in batches
class DeleteMsgHandlerSpec
  extends BasicSpec
    with ActorSpec {

  lazy val mockActor: ActorRef = system.actorOf(MockPersistentActor.props(appConfig, executionContextProvider.futureExecutionContext))

  "PersistentActor" - {

    "when tried to persists events" - {
      "should be successfully persisted" in {
        mockActor ! PersistEvents(3000)
        expectMsg(Done)
      }
    }

    "when tried to delete events" - {
      "should be successfully deleted" in {
        //here we are indirectly testing success and failure scenario along with max batch size as well.
        mockActor ! StartMsgDeletion
        expectMsgType[Done.type]
        val expectedEventDeletionInBatch = Seq(700, 1700, 2700, 3000)
        EventFilter.info(pattern = s"delete message completed", occurrences = 1) intercept {
          expectedEventDeletionInBatch.foreach { batchSize =>
            checkBatchSuccessMsg(batchSize)
          }
        }
      }
    }
  }

  def checkBatchSuccessMsg(deletedEvent: Int, occurrences: Int = 1): Unit = {
    EventFilter.info(
      pattern = s"delete message successful: DeleteMessagesSuccess\\($deletedEvent\\)",
      occurrences = occurrences) intercept {
      mockActor ! GetPersistentActorDetail
      expectMsgType[PersistentActorDetail]
    }
  }

  override def overrideConfig: Option[Config] = Option {
    ConfigFactory.parseString {
      """
         akka.loglevel = INFO
         akka.test.filter-leeway = 20s   # to make the event filter run for longer time
         akka.logging-filter = "com.evernym.verity.actor.testkit.logging.TestFilter"
        """
    }.withFallback(configForDeleteEventFailure)
  }

  def configForDeleteEventFailure: Config =  {
    AkkaTestBasic.customJournal("com.evernym.verity.actor.persistence.eventDeletion.FailsOnDeleteEventsTestJournal")
  }

  lazy val ecp: ExecutionContextProvider = new ExecutionContextProvider(appConfig)
  override def executionContextProvider: ExecutionContextProvider = ecp
}

class MockPersistentActor(val appConfig: AppConfig, executionContext: ExecutionContext)
  extends BasePersistentActor
    with DefaultPersistenceEncryption
    with ActorLogging {

  var totalEventsToBePersisted = 0
  var totalEventsPersisted = 0

  override def receiveCmd: Receive = {

    case PersistEvents(totalEvents) =>
      totalEventsToBePersisted = totalEvents
      (1 to totalEvents).foreach { _ =>
        writeAndApply(
          ConfigUpdated(
            "config-name",
            "config-value",
            getMillisFromZonedDateTime(getCurrentUTCZonedDateTime))
        )
      }

    case StartMsgDeletion =>
      deleteMessagesExtended(lastSequenceNr)
      sender ! Done

  }

  override def receiveEvent: Receive = {
    case _: ConfigUpdated =>
      totalEventsPersisted += 1
      if (totalEventsToBePersisted == totalEventsPersisted) {
        sender ! Done
      }
  }

  override def onDeleteMessageSuccess(dms: DeleteMessagesSuccess): Unit = {
    log.info("delete message successful: " + dms)
  }

  override def onDeleteMessageFailure(dmf: DeleteMessagesFailure): Unit = {

  }

  override def postAllMsgsDeleted(): Unit = {
    log.info("delete message completed")
  }

  override protected def initialBatchSize = 700
  override protected def batchIntervalInSeconds = 1

  /**
   * custom thread pool executor
   */
  override def futureExecutionContext: ExecutionContext = executionContext
}

case class PersistEvents(totalEvents: Int) extends ActorMessage
case object StartMsgDeletion extends ActorMessage

object MockPersistentActor {
  def props(appConfig: AppConfig, executionContext: ExecutionContext): Props =
    Props(new MockPersistentActor(appConfig, executionContext))
}

class FailsOnDeleteEventsTestJournal extends TestJournal {
  type SeqNo = Long

  //this is to simulate DeleteMessageFailure scenario
  var failStatus: Map[SeqNo, Boolean] = Map(
    150l -> false,
    1550l -> false
  )
  override def asyncDeleteMessagesTo(persistenceId: String, toSequenceNr: Long): Future[Unit] = {
    failStatus.get(toSequenceNr) match {
      case Some(false)  =>
        failStatus = failStatus + (toSequenceNr -> true)
        Future.failed(new RuntimeException(s"PURPOSEFULLY failing in test (thrown at = $getCurrentUTCZonedDateTime)"))
      case _            =>
        super.asyncDeleteMessagesTo(persistenceId, toSequenceNr)
    }
  }
}
