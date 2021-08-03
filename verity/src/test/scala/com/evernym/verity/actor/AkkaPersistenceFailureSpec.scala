package com.evernym.verity.actor

import akka.actor.{ActorRef, Props}
import akka.persistence.{AtomicWrite, PersistentRepr}
import akka.serialization.SerializationExtension
import com.evernym.verity.util2.Exceptions.{BadRequestErrorException, InternalServerErrorException}
import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.util2.Status._
import com.evernym.verity.actor.persistence.BasePersistentActor
import com.evernym.verity.actor.testkit.{AkkaTestBasic, PersistentActorSpec, TestAppConfig}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.protocol.engine.DID
import com.evernym.verity.protocol.protocols.walletBackup.legacy.BackupStored
import com.evernym.verity.testkit.BasicSpec
import com.google.protobuf.ByteString
import com.typesafe.config.Config

import scala.collection.immutable
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

class AkkaPersistenceFailureSpec extends PersistentActorSpec with BasicSpec {

  val pa: ActorRef = system.actorOf(Props(new DummyActor(ecp.futureExecutionContext)))

  "A persisting actor" - {
    "fails on large event" in {
      val tooLargeData: Array[Byte] = Array.fill(700000){'a'}
      pa ! BadPersistenceData(tooLargeData)
      expectMsg("persistence failure handled")
    }
  }

  override def overrideConfig: Option[Config] = Option {
    AkkaTestBasic.customJournal("com.evernym.verity.actor.FailsOnLargeEventTestJournal")
  }

  lazy val ecp: ExecutionContextProvider = new ExecutionContextProvider(appConfig)

  override def executionContextProvider: ExecutionContextProvider = ecp
}

class FailsOnLargeEventTestJournal extends TestJournal {

  override def asyncWriteMessages(messages: immutable.Seq[AtomicWrite]):  Future[immutable.Seq[Try[Unit]]] = {

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

case class AddData(did: DID, data: String) extends ActorMessage

case class GetData(did: DID) extends ActorMessage

case class BadPersistenceData(data: Array[Byte]) extends ActorMessage

case object RestartNow extends ActorMessage

class DummyActor(executionContext: ExecutionContext) extends BasePersistentActor {
  override def futureExecutionContext: ExecutionContext = executionContext

  lazy val appConfig: AppConfig = new TestAppConfig
  var didData: Map[String, String] = Map.empty

  override def persistenceEncryptionKey: String = "test-key"

  val receiveEvent: Receive = {
    case e: MockEvent3 => didData += e.did -> e.data
  }

  lazy val receiveCmd: Receive = {
    case ad: AddData if didData.contains(ad.did) =>
      throw new BadRequestErrorException(ALREADY_EXISTS.statusCode)

    case ad: AddData =>
      writeApplyAndSendItBack(MockEvent3(ad.did, ad.data))

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
