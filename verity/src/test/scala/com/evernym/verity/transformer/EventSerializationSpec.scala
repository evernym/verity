package com.evernym.verity.transformer

import akka.actor.{ActorRef, Props}
import akka.serialization.SerializationExtension
import com.evernym.verity.Exceptions.{BadRequestErrorException, InternalServerErrorException}
import com.evernym.verity.Status._
import com.evernym.verity.actor._
import com.evernym.verity.actor.event.serializer.{EventCodeMapper, EventSerializer, ProtoBufSerializer}
import com.evernym.verity.actor.persistence.BasePersistentActor
import com.evernym.verity.actor.testkit.checks.UNSAFE_IgnoreLog
import com.evernym.verity.actor.testkit.{MockEventSerializer, PersistentActorSpec, TestAppConfig}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.evernym.verity.protocol.engine.DID
import com.evernym.verity.protocol.protocols.walletBackup.BackupStored
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.ActorErrorResp
import com.google.protobuf.ByteString
import com.typesafe.scalalogging.Logger

class EventSerializationSpec extends PersistentActorSpec with BasicSpec with MockEventSerializer {

  val da: ActorRef = system.actorOf(Props(new DummyActor), "dummy-actor")

  "Event Serializer " - {
    "should be able to encrypt, serialize, deserialize and decryption of event using Proto serializer" in {

      // Get the Serialization Extension
      val serialization = SerializationExtension(system)

      //Event to be tested
      val event = TestEventAddData1(1, 21, "test event", ByteString.copyFromUtf8("test"))

      // Let event be encrypted
      val encrypted = TestSerializer.getTransformedEvent(event, "test-key")

      // Find the Serializer for it
      val serializer = serialization.findSerializerFor(encrypted).asInstanceOf[ProtoBufSerializer]

      // Serialize event
      val serialized = serializer.toBinary(encrypted)

      // Deserialize event
      val deserialized = serializer.fromBinary(serialized, manifest = serializer.TRANSFORMED_EVENT_MANIFEST).asInstanceOf[TransformedEvent]

      // Decrypt event
      val decrypted = TestSerializer.getDeSerializedEvent(deserialized, "test-key")

      // TA DA!
      decrypted should be(event)
    }
  }

  "EventSerializer" - {

    "should be able to encrypt/decrypt and serialize events" in {
      val event = TestEventAddData1(1, 21, "test event", ByteString.copyFromUtf8("test"))
      val encryptedEvent = TestSerializer.getTransformedEvent(event, "test-key")
      val decryptedEvent = TestSerializer.getDeSerializedEvent(encryptedEvent, "test-key")
      event shouldBe decryptedEvent
    }

    "should throw error if mapping is not available" in {
      val event = KeyCreated("2314")
      assertThrows[RuntimeException] {
        TestSerializer.getTransformedEvent(event, "test-key")
      }

    }

    "should be able to adapt to schema evolution (Event renaming and field addition)" in {
      val event = TestEventAddData1(1, 21, "test event", ByteString.copyFromUtf8("test"))
      val encryptedEvent = TestSerializer.getTransformedEvent(event, "test-key")

      //New mapping with renamed events
      object RenamedTestEventMappings extends EventCodeMapper {
        val TEST_EVENT = -1

        override val eventCodeMapping = Map(
          TEST_EVENT -> TestEventAddData2
        )
      }
      object TestSerializerCommonHelper extends EventSerializer {
        override val eventMapper: EventCodeMapper = RenamedTestEventMappings
      }
      val decryptedEvent = TestSerializerCommonHelper.getDeSerializedEvent(encryptedEvent, "test-key")
      TestEventAddData2(1, 21, "test event") shouldBe decryptedEvent
    }
  }

  "Dummy test actor" - {

    "when sent AddData" - {
      "should be able to successfully add it" in {
        da ! AddData("1234", "test-data")
        expectMsg(TestEventDataAdded("1234", "test-data"))
      }
    }

    "when sent GetData" - {
      "should be able to successfully get it" in {
        da ! GetData("1234")
        expectMsg(Some("test-data"))
      }
    }


    "when sent GetData after restart" - {
      "should be able to successfully get it again" taggedAs (UNSAFE_IgnoreLog) in {
        da ! RestartNow
        expectMsgPF() {
          case ber: ActorErrorResp if ber.statusCode == UNHANDLED.statusCode =>
        }
        da ! GetData("1234")
        expectMsg(Some("test-data"))
      }
    }

  }
}


//cmds
case class AddData(did: DID, data: String) extends ActorMessageClass

case class GetData(did: DID) extends ActorMessageClass

case class BadPersistenceData(data: Array[Byte]) extends ActorMessageClass

case object RestartNow extends ActorMessageObject

class DummyActor extends BasePersistentActor with MockEventSerializer {

  lazy val appConfig: AppConfig = new TestAppConfig
  var didData: Map[String, String] = Map.empty
  override val eventSerializer: EventSerializer = TestSerializer
  val logger: Logger = getLoggerByClass(getClass)


  override def persistenceEncryptionKey: String = "test-key"

  val receiveEvent: Receive = {
    case e: TestEventDataAdded => didData += e.did -> e.data
  }

  lazy val receiveCmd: Receive = {
    case ad: AddData if didData.contains(ad.did) =>
      throw new BadRequestErrorException(ALREADY_EXISTS.statusCode)

    case ad: AddData =>
      writeApplyAndSendItBack(TestEventDataAdded(ad.did, ad.data))

    case gd: GetData =>
      sender ! didData.get(gd.did)

    case bd: BadPersistenceData =>
      persistExt(BackupStored(ByteString.copyFrom(bd.data)))(() => _)

    case RestartNow =>
      val cre = new InternalServerErrorException(UNHANDLED.statusCode, Option(UNHANDLED.statusMsg))
      //below is just so that it doesn't print whole stack trace which may confuse someone if this is expected exception or not etc
      cre.setStackTrace(Array())
      throw cre
  }

  private def afterFailureTest(data: Option[String] = None): Unit = {
    logger.info("Successful Persist after failure")
    sender() ! "persistence failure handled"
  }

  final override def onPersistFailure(cause: Throwable, event: Any, seqNr: Long): Unit = {
    val errorMsg =
      "actor persist event failed (" +
        "possible-causes: database not reachable/up/responding, required tables are not created etc, " +
        s"persistence-id: $persistenceId, " +
        s"error-msg: ${cause.getMessage})"

    afterFailureTest(Some(errorMsg))
  }
}