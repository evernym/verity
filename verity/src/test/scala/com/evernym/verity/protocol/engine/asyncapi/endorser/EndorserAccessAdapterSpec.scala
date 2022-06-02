package com.evernym.verity.protocol.engine.asyncapi.endorser

import akka.Done
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKitBase}
import akka.util.Timeout
import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.actor.base.CoreActor
import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.actor.testkit.actor.ActorSystemVanilla
import com.evernym.verity.config.AppConfig
import com.evernym.verity.eventing.ports.producer.ProducerPort
import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByClass
import com.evernym.verity.protocol.container.actor.AsyncAPIContext
import com.evernym.verity.protocol.engine.ProtoRef
import com.evernym.verity.protocol.engine.asyncapi.{AsyncOpRunner, RoutingContext}
import com.evernym.verity.storage_services.StorageAPI
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.testkit.mock.blob_store.MockBlobStore
import com.evernym.verity.util2.{ExecutionContextProvider, PolicyElements, RetentionPolicy}
import com.typesafe.scalalogging.Logger

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.{Success, Try}


class EndorserAccessAdapterSpec
  extends TestKitBase
    with ImplicitSender
    with BasicSpec {

  "EventAccessAdapter" - {
    "when asked to send endorsement txn request" - {
      "should be successful" in {
        mockActorContainer ! EndorseTxn("endorsement_txn_req", "endorserDID", "indy:sov:mainnet", "indy")
        expectMsgType[Done]
        storageAPI.bucketStore.size shouldBe 1
      }
    }
  }

  val appConfig = new TestAppConfig()
  val system: ActorSystem = ActorSystemVanilla("test", appConfig.config)
  val dataRetentionPolicy: Option[RetentionPolicy] = Option(RetentionPolicy(
    """{"expire-after-days":1 day,"expire-after-terminal-state":true}""",
    PolicyElements(Duration.apply(1, DAYS), expireAfterTerminalState = true)
  ))


  lazy val ecp = new ExecutionContextProvider(appConfig)
  implicit lazy val executionContext: ExecutionContext = ecp.futureExecutionContext
  lazy val storageAPI = new MockBlobStore(appConfig, ecp.futureExecutionContext)(system)
  lazy val mockActorContainer: ActorRef = system.actorOf(MockActorContainer.props(appConfig, storageAPI, dataRetentionPolicy))

  private def blobStoreBucket: String = appConfig
    .config
    .getConfig("verity.blob-store")
    .getString("bucket-name")

}

case class EndorseTxn(txn: String, endorser: String, vdr: String, vdrType: String) extends ActorMessage

object MockActorContainer {
  def props(appConfig: AppConfig, storageAPI: StorageAPI, dataRetentionPolicy: Option[RetentionPolicy])(implicit ec: ExecutionContext): Props =
    Props(new MockActorContainer(appConfig, storageAPI, dataRetentionPolicy))
}

class MockActorContainer(val appConfig: AppConfig,
                         storageAPI: StorageAPI,
                         dataRetentionPolicy: Option[RetentionPolicy])(implicit ec: ExecutionContext)
  extends CoreActor
    with AsyncOpRunner {

  override def receiveCmd: Receive = {
    case EndorseTxn(payload, endorser, vdr, vdrType) =>
      endorserAccessAdapter.endorseTxn(payload, vdrType)(handler(sender()))
  }

  def handler(sender: ActorRef)(resp: Try[Unit]): Unit = {
    resp match {
      case Success(value) => sender ! Done
      case other          => throw new RuntimeException("unhandled response: " + other)
    }
  }

  override protected def runFutureAsyncOp(op: => Future[Any]): Unit = {
    val result = op
    result.onComplete(r => executeCallbackHandler(r))
  }

  override def postAllAsyncOpsCompleted(): Unit = {}


  val routingContext: RoutingContext = RoutingContext("domainId", "relId", "pinstid124", "threadid1", ProtoRef("write-schema", "0.6"))
  val endorserAccessAdapter = new EndorserAccessAdapter(
    routingContext,
    new MockProducerAdapter,
    storageAPI,
    null,
    dataRetentionPolicy
  )

  implicit lazy val responseTimeout: Timeout = Timeout(10.seconds)
  implicit lazy val asyncAPIContext: AsyncAPIContext = AsyncAPIContext(appConfig, self, context, responseTimeout)
  implicit def asyncOpRunner: AsyncOpRunner = this
  override def abortTransaction(): Unit = ???
  protected def runAsyncOp(op: => Any): Unit = ???

  override val logger: Logger = getLoggerByClass(getClass)
}

class MockProducerAdapter extends ProducerPort {

  override def send(topic: String, payload: Array[Byte]): Future[Done] =
    Future.successful(Done)
}

case class Published(storageId: String) extends ActorMessage