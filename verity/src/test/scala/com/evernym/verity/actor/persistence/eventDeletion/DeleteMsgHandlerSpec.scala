package com.evernym.verity.actor.persistence.eventDeletion

import akka.actor.{ActorLogging, ActorRef, Props}
import akka.persistence.{DeleteMessagesFailure, DeleteMessagesSuccess}
import akka.testkit.EventFilter
import com.evernym.verity.actor.base.Done
import com.evernym.verity.actor.{ActorMessage, ItemUpdated, TestJournal}
import com.evernym.verity.actor.persistence.{BasePersistentActor, DefaultPersistenceEncryption}
import com.evernym.verity.actor.testkit.{ActorSpec, AkkaTestBasic}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.util.TimeZoneUtil.{getCurrentUTCZonedDateTime, getMillisFromZonedDateTime}
import com.typesafe.config.{Config, ConfigFactory}

import scala.concurrent.Future

//tests how deletion of events (messages) handled in batches
class DeleteMsgHandlerSpec
  extends BasicSpec
    with ActorSpec {

  lazy val mockActor: ActorRef = system.actorOf(MockPersistentActor.props(appConfig))

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
        EventFilter.info(pattern = "delete message successful: DeleteMessagesSuccess(.*)", occurrences = 8) intercept {
          mockActor ! StartMsgDeletion
          expectMsg(Done)
        }
      }
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
}

class MockPersistentActor(val appConfig: AppConfig)
  extends BasePersistentActor
    with DefaultPersistenceEncryption
    with ActorLogging {

  var totalEventsToBePersisted = 0
  var totalEventsPersisted = 0

  override def receiveCmd: Receive = {

    case PersistEvents(totalEvents) =>
      totalEventsToBePersisted = totalEvents
      (1 to totalEvents).foreach { i =>
        writeAndApply(
        ItemUpdated(
          i.toString,
          0,      //status would be always from this new request message
          "detail",     //detail would be always from this new request message
          isFromMigration = false,
          getMillisFromZonedDateTime(getCurrentUTCZonedDateTime))
        )
      }

    case StartMsgDeletion =>
      deleteMessagesExtended(lastSequenceNr)
      sender ! Done

  }

  override def receiveEvent: Receive = {
    case _: ItemUpdated =>
      totalEventsPersisted += 1
      if (totalEventsToBePersisted == totalEventsPersisted) {
        sender ! Done
      }
  }

  override def postAllMsgsDeleted(): Unit = {}

  override def onDeleteMessageSuccess(dms: DeleteMessagesSuccess): Unit = {
    log.info("delete message successful: " + dms)
  }

  override def onDeleteMessageFailure(dmf: DeleteMessagesFailure): Unit = {

  }

  override protected val initialBatchSize = 50
  override protected val maxBatchSize = 1000
  override protected val batchSizeMultiplier = 2
  override protected val batchIntervalInSeconds = 2

}

case class PersistEvents(totalEvents: Int) extends ActorMessage
case object StartMsgDeletion extends ActorMessage

object MockPersistentActor {
  def props(appConfig: AppConfig): Props =
    Props(new MockPersistentActor(appConfig))
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
