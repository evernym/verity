package com.evernym.verity.actor

import akka.actor.{ActorRef, Props}
import akka.persistence.PersistentRepr
import akka.serialization.SerializationExtension
import com.evernym.verity.actor.testkit.{AkkaTestBasic, PersistentActorSpec}
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.transformer.{BadPersistenceData, DummyActor}

class AkkaPersistenceFailureSpec extends PersistentActorSpec with BasicSpec {

  val pa: ActorRef = system.actorOf(Props(new DummyActor))

  "A persisting actor" - {
    "fails on large event" in {
      val tooLargeData: Array[Byte] = Array.fill(700000){'a'}
      pa ! BadPersistenceData(tooLargeData)
      expectMsg("persistence failure handled")
    }
  }

  override def overrideConfig = Option {
      AkkaTestBasic.journalFailingOnLargeEvents
  }
}

class FailsOnLargeEventTestJournal extends TestJournal {

  override def asyncWriteMessages(messages: _root_.scala.collection.immutable.Seq[_root_.akka.persistence.AtomicWrite]):
  _root_.scala.concurrent.Future[_root_.scala.collection.immutable.Seq[_root_.scala.util.Try[Unit]]] = {

    /**
     * This is dynamodb's event size restriction
     * Wallet Backup first exposed this as being an issue but will eventually use S3 rather than dynamodb
     */
    val MAX_SIZE = 399999

    val payloads = messages.flatMap(_.payload)

    if (payloads.exists(p => toEventSize(p) > MAX_SIZE)) {
      throw new Exception("can't persist an event of size more than 400 KB")
    } else {
      super.asyncWriteMessages(messages)
    }
  }

  def toEventSize(repr: PersistentRepr): Int = {
    import java.nio.ByteBuffer
    val serialization = SerializationExtension(context.system)
    val reprPayload: AnyRef = repr.payload.asInstanceOf[AnyRef]
    val serialized = ByteBuffer.wrap(serialization.serialize(reprPayload).get).array
    serialized.length
  }

}


