package com.evernym.verity.actor.persistence

import java.util.concurrent.TimeoutException

import akka.actor.{ActorRef, ActorSystem, Props, Terminated}
import akka.util.Timeout
import akka.pattern.ask
import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.actor.base.CoreActorExtended
import com.evernym.verity.actor.testkit.{AkkaTestBasic, TestAppConfig}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.ConfigConstants._
import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByClass
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.util.TestExecutionContextProvider
import com.typesafe.config.{Config, ConfigFactory, ConfigValueFactory}
import com.typesafe.scalalogging.Logger

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._


class PersistentActorReceiveTimeoutsSpec extends BasicSpec {
  import MockActor._

  val logger: Logger = getLoggerByClass(classOf[PersistentActorReceiveTimeoutsSpec])

  implicit val timeout: Timeout = Timeout(15.seconds)
  implicit val executionContext: ExecutionContext = ExecutionContext.Implicits.global

  implicit val system: ActorSystem = AkkaTestBasic.system()

  lazy val ecp: ExecutionContextProvider = TestExecutionContextProvider.ecp
  lazy val futureExecutionContext: ExecutionContext = ecp.futureExecutionContext

  timeoutsSpec()

  def buildMockActorSetupDetail(childName: String): MockActorSetupDetail = {
    val nextId = nextActorId
    MockActorSetupDetail(s"Watcher-$nextId", s"$childName-$nextId")
  }

  def timeoutsSpec(): Unit = {

    "ActorShutdownTests" - {
      "BasePersistentActor" - {

        "When no timeout configuration present" - {
          "should follow hardcoded timeout" in {
            val mockActorSetupDetail = buildMockActorSetupDetail("BaseChild")
            val conf = ConfigFactory.load()
            checkPersistentActor(mockActorSetupDetail.watcherEntityId, DEFAULT_RECEIVE_TIMEOUT.seconds,
              WatchBaseActor(mockActorSetupDetail.childEntityId), conf)
          }
        }

        "When configured only category default" - {
          "should use category default timeout" in {
            val mockActorSetupDetail = buildMockActorSetupDetail("BaseChild")
            val conf: Config = ConfigFactory.load()
              .withValue(PERSISTENT_ACTOR_BASE_RECEIVE_TIMEOUT_SECONDS, ConfigValueFactory.fromAnyRef(5))
            checkPersistentActor(mockActorSetupDetail.watcherEntityId, 5.seconds,
              WatchBaseActor(mockActorSetupDetail.childEntityId), conf)
          }
        }

        "When configured only category default set to 0" - {
          "should not terminate actor (indefinite timeout)" in {
            val mockActorSetupDetail = buildMockActorSetupDetail("BaseChild")
            val conf: Config = ConfigFactory.load()
              .withValue(PERSISTENT_ACTOR_BASE_RECEIVE_TIMEOUT_SECONDS, ConfigValueFactory.fromAnyRef(0))
            checkPersistentActor(mockActorSetupDetail.watcherEntityId, Duration.Undefined,
              WatchBaseActor(mockActorSetupDetail.childEntityId), conf)
          }
        }

        "When configured category and entityType timeouts" - {
          "should use entityType timeout" in {
            val mockActorSetupDetail = buildMockActorSetupDetail("BaseChild")
            val conf: Config = ConfigFactory.load()
              .withValue(PERSISTENT_ACTOR_BASE_RECEIVE_TIMEOUT_SECONDS, ConfigValueFactory.fromAnyRef(3))
              .withValue(s"$PERSISTENT_ACTOR_BASE.${mockActorSetupDetail.entityType}.$RECEIVE_TIMEOUT_SECONDS",
                ConfigValueFactory.fromAnyRef(5))

            checkPersistentActor(mockActorSetupDetail.watcherEntityId, 5.seconds,
              WatchBaseActor(mockActorSetupDetail.childEntityId), conf)
          }
        }

        "When configured category and entityType timeouts, with entityType timeout set to 0" - {
          "should not terminate actor (indefinite timeout)" in {
            val mockActorSetupDetail = buildMockActorSetupDetail("BaseChild")
            val conf: Config = ConfigFactory.load()
              .withValue(PERSISTENT_ACTOR_BASE_RECEIVE_TIMEOUT_SECONDS, ConfigValueFactory.fromAnyRef(3))
              .withValue(s"$PERSISTENT_ACTOR_BASE.${mockActorSetupDetail.entityType}.$RECEIVE_TIMEOUT_SECONDS",
                ConfigValueFactory.fromAnyRef(0))

            checkPersistentActor(mockActorSetupDetail.watcherEntityId, Duration.Undefined,
              WatchBaseActor(mockActorSetupDetail.childEntityId), conf)
          }
        }

        "When configured category, entityType and entityId timeouts" - {
          "should use entityId timeout" in {
            val mockActorSetupDetail = buildMockActorSetupDetail("BaseChild")

            val conf: Config = ConfigFactory.load()
              .withValue(PERSISTENT_ACTOR_BASE_RECEIVE_TIMEOUT_SECONDS, ConfigValueFactory.fromAnyRef(2))
              .withValue(s"$PERSISTENT_ACTOR_BASE.${mockActorSetupDetail.entityType}.$RECEIVE_TIMEOUT_SECONDS", ConfigValueFactory.fromAnyRef(7))
              .withValue(s"$PERSISTENT_ACTOR_BASE.${mockActorSetupDetail.entityType}.${mockActorSetupDetail.childEntityId}.$RECEIVE_TIMEOUT_SECONDS", ConfigValueFactory.fromAnyRef(5))

            checkPersistentActor(mockActorSetupDetail.watcherEntityId, 5.seconds,
              WatchBaseActor(mockActorSetupDetail.childEntityId), conf)
          }
        }

        "When configured category, entityType and entityId timeouts, with entityId timeout set to 0" - {
          "should not terminate actor (indefinite timeout)" in {
            val mockActorSetupDetail = buildMockActorSetupDetail("BaseChild")

            val conf: Config = ConfigFactory.load()
              .withValue(PERSISTENT_ACTOR_BASE_RECEIVE_TIMEOUT_SECONDS, ConfigValueFactory.fromAnyRef(2))
              .withValue(s"$PERSISTENT_ACTOR_BASE.${mockActorSetupDetail.entityType}.$RECEIVE_TIMEOUT_SECONDS", ConfigValueFactory.fromAnyRef(7))
              .withValue(s"$PERSISTENT_ACTOR_BASE.${mockActorSetupDetail.entityType}.${mockActorSetupDetail.childEntityId}.$RECEIVE_TIMEOUT_SECONDS", ConfigValueFactory.fromAnyRef(0))

            checkPersistentActor(mockActorSetupDetail.watcherEntityId, Duration.Undefined,
              WatchBaseActor(mockActorSetupDetail.childEntityId), conf)
          }
        }
      }
      "SingletonChildrenPersistentActor" - {
        "When not configured" - {
          "should follow hardcoded timeout" in {
            val mockActorSetupDetail = buildMockActorSetupDetail("SingletonChild")
            val conf = ConfigFactory.load()

            checkPersistentActor(mockActorSetupDetail.watcherEntityId, DEFAULT_RECEIVE_TIMEOUT.seconds,
              WatchSingletonChildActor(mockActorSetupDetail.childEntityId), conf)
          }
        }

        "When configured only category default" - {
          "should use category default timeout" in {
            val mockActorSetupDetail = buildMockActorSetupDetail("SingletonChild")

            val conf: Config = ConfigFactory.load()
              .withValue(PERSISTENT_SINGLETON_CHILDREN_RECEIVE_TIMEOUT_SECONDS, ConfigValueFactory.fromAnyRef(5))

            checkPersistentActor(mockActorSetupDetail.watcherEntityId, 5.seconds,
              WatchSingletonChildActor(mockActorSetupDetail.childEntityId), conf)
          }
        }

        "When configured only category default set to 0" - {
          "should not terminate actor (indefinite timeout)" in {
            val mockActorSetupDetail = buildMockActorSetupDetail("SingletonChild")
            val conf: Config = ConfigFactory.load()
              .withValue(PERSISTENT_SINGLETON_CHILDREN_RECEIVE_TIMEOUT_SECONDS, ConfigValueFactory.fromAnyRef(5))

            checkPersistentActor(mockActorSetupDetail.watcherEntityId, 5.seconds,
              WatchSingletonChildActor(mockActorSetupDetail.childEntityId), conf)
          }
        }

        "When configured category and entityType timeouts" - {
          "should use entityType timeout" in {
            val mockActorSetupDetail = buildMockActorSetupDetail("SingletonChild")

            val conf: Config = ConfigFactory.load()
              .withValue(PERSISTENT_SINGLETON_CHILDREN_RECEIVE_TIMEOUT_SECONDS, ConfigValueFactory.fromAnyRef(3))
              .withValue(s"$PERSISTENT_SINGLETON_CHILDREN.${mockActorSetupDetail.entityType}.$RECEIVE_TIMEOUT_SECONDS",
                ConfigValueFactory.fromAnyRef(5))

            checkPersistentActor(mockActorSetupDetail.watcherEntityId, 5.seconds,
              WatchSingletonChildActor(mockActorSetupDetail.childEntityId), conf)
          }
        }

        "When configured category and entityType timeouts, with entityType timeout set to 0" - {
          "should not terminate actor (indefinite timeout)" in {
            val mockActorSetupDetail = buildMockActorSetupDetail("SingletonChild")

            val conf: Config = ConfigFactory.load()
              .withValue(PERSISTENT_SINGLETON_CHILDREN_RECEIVE_TIMEOUT_SECONDS, ConfigValueFactory.fromAnyRef(3))
              .withValue(s"$PERSISTENT_SINGLETON_CHILDREN.${mockActorSetupDetail.entityType}.$RECEIVE_TIMEOUT_SECONDS",
                ConfigValueFactory.fromAnyRef(0))

            checkPersistentActor(mockActorSetupDetail.watcherEntityId, Duration.Undefined,
              WatchSingletonChildActor(mockActorSetupDetail.childEntityId), conf)
          }
        }

        "When configured category, entityType and entityId timeouts" - {
          "should use entityId timeout" in {
            val mockActorSetupDetail = buildMockActorSetupDetail("SingletonChild")

            val conf: Config = ConfigFactory.load()
              .withValue(PERSISTENT_SINGLETON_CHILDREN_RECEIVE_TIMEOUT_SECONDS, ConfigValueFactory.fromAnyRef(2))
              .withValue(s"$PERSISTENT_SINGLETON_CHILDREN.${mockActorSetupDetail.entityType}.$RECEIVE_TIMEOUT_SECONDS",
                ConfigValueFactory.fromAnyRef(7))
              .withValue(s"$PERSISTENT_SINGLETON_CHILDREN.${mockActorSetupDetail.entityType}.${mockActorSetupDetail.childEntityId}.$RECEIVE_TIMEOUT_SECONDS",
                ConfigValueFactory.fromAnyRef(5))

            checkPersistentActor(mockActorSetupDetail.watcherEntityId, 5.seconds,
              WatchSingletonChildActor(mockActorSetupDetail.childEntityId), conf)
          }
        }

        "When configured category, entityType and entityId timeouts, with entityId timeout set to 0" - {
          "should not terminate actor (indefinite timeout)" in {
            val mockActorSetupDetail = buildMockActorSetupDetail("SingletonChild")

            val conf: Config = ConfigFactory.load()
              .withValue(PERSISTENT_SINGLETON_CHILDREN_RECEIVE_TIMEOUT_SECONDS, ConfigValueFactory.fromAnyRef(2))
              .withValue(s"$PERSISTENT_SINGLETON_CHILDREN.${mockActorSetupDetail.entityType}.$RECEIVE_TIMEOUT_SECONDS",
                ConfigValueFactory.fromAnyRef(7))
              .withValue(s"$PERSISTENT_SINGLETON_CHILDREN.${mockActorSetupDetail.entityType}.${mockActorSetupDetail.childEntityId}.$RECEIVE_TIMEOUT_SECONDS",
                ConfigValueFactory.fromAnyRef(0))

            checkPersistentActor(mockActorSetupDetail.watcherEntityId, Duration.Undefined,
              WatchSingletonChildActor(mockActorSetupDetail.childEntityId), conf)
          }
        }
      }
    }
  }

  def checkPersistentActor(watcherEntityId: String,
                           expectedTimeout: Duration,
                           msg: ActorMessage,
                           conf: Config): Unit = {
    val appConfig = new TestAppConfig(Option(conf))
    val watcher = system.actorOf(Props(new WatcherActor(appConfig, expectedTimeout, futureExecutionContext)), name = watcherEntityId)
    val response: Future[Any] = watcher ? msg

    try {
      Await.result(response, timeout.duration) match {
        case ActorStopped(awakeMillis)      => logger.info(s"Actor was awake for $awakeMillis milliseconds")
        case WrongTimeoutReported(timeout)  => fail(s"Timeout value used is wrong, should be $expectedTimeout but it is $timeout")
        case x => throw new Exception(s"Wrong result: $x")
      }
    } catch {
      case _: TimeoutException if ! expectedTimeout.isFinite() =>
        logger.info("Actor not terminated, and that is expected.")
      case e: TimeoutException =>
        logger.error("Actor not terminated, and that was NOT expected.")
        throw e
      case e: RuntimeException =>
        logger.error(s"Unexpected exception: $e")
        throw e
    }
  }

}

object MockActor {
  val DEFAULT_RECEIVE_TIMEOUT = 10
  var idCount: Int = 0
  def nextActorId: Int = {
    idCount += 1
    idCount
  }

  case class WatchBaseActor(name: String) extends ActorMessage
  case class WatchSingletonChildActor(name: String) extends ActorMessage
  case class ReceiveTimeoutQuestion() extends ActorMessage
  case class ReceiveTimeoutAnswer(timeout: Duration) extends ActorMessage
  case class WrongTimeoutReported(timeout: Duration) extends ActorMessage
  case class ActorStopped(awakeMillis: Long) extends ActorMessage
}

import MockActor._


class MockBaseActor(val appConfig: AppConfig, executionContext: ExecutionContext) extends BasePersistentTimeoutActor {

  override def receiveCmd: Receive = {
    case ReceiveTimeoutQuestion() =>
      logger.info(s"$self: my timeout is: $entityReceiveTimeout")
      sender ! ReceiveTimeoutAnswer(entityReceiveTimeout)
    case x => logger.info(s"receiveCmd: $x")
  }

  override def receiveEvent: Receive = {
    case x => logger.info(s"receiveEvent: $x")
  }

  override def persistenceEncryptionKey: String = "klsd89894kdsjisdji4"

  override val defaultReceiveTimeoutInSeconds: Int = DEFAULT_RECEIVE_TIMEOUT

  /**
   * custom thread pool executor
   */
  override def futureExecutionContext: ExecutionContext = executionContext
}

class MockSingletonChildActor(val appConfig: AppConfig, executionContext: ExecutionContext) extends SingletonChildrenPersistentActor {

  override def receiveCmd: Receive = {
    case ReceiveTimeoutQuestion() =>
      logger.info(s"$self: my timeout is: $entityReceiveTimeout")
      sender ! ReceiveTimeoutAnswer(entityReceiveTimeout)
    case x => logger.info(s"receiveCmd: $x")
  }

  override def receiveEvent: Receive = {
    case x => logger.info(s"receiveEvent: $x")
  }

  override def persistenceEncryptionKey: String = "klsd89894kdsjisdji4"

  override val defaultReceiveTimeoutInSeconds: Int = DEFAULT_RECEIVE_TIMEOUT

  /**
   * custom thread pool executor
   */
  override def futureExecutionContext: ExecutionContext = executionContext
}

class WatcherActor(appConfig: AppConfig, expectedTimeout: Duration, executionContext: ExecutionContext)
  extends CoreActorExtended {

  val logger: Logger = getLoggerByClass(classOf[WatcherActor])
  var senderActorRef: ActorRef = ActorRef.noSender
  var childStartedTime: Long = _
  var isChildActorStartedSuccessfully = false

  override def receiveCmd: Receive = {
    case WatchBaseActor(name) =>
      senderActorRef = sender()
      val child: ActorRef = context.actorOf(Props(new MockBaseActor(appConfig, executionContext)), name = name)
      context.watch(child)
      child ! ReceiveTimeoutQuestion()
      childStartedTime = System.currentTimeMillis()

    case WatchSingletonChildActor(name) =>
      senderActorRef = sender()
      val child: ActorRef = context.actorOf(Props(new MockSingletonChildActor(appConfig, executionContext)), name = name)
      context.watch(child)
      child ! ReceiveTimeoutQuestion()
      childStartedTime = System.currentTimeMillis()

    case ReceiveTimeoutAnswer(timeout) =>
      isChildActorStartedSuccessfully = true
      logger.info(s"${sender()} is alive and its timeout is: $timeout")

      if (timeout.isFinite() && timeout != expectedTimeout)
        senderActorRef ! WrongTimeoutReported(timeout)
  }

  override def sysCmdHandler: Receive = {
    case Terminated(watched) if isChildActorStartedSuccessfully =>
      logger.info(s"OMG, they killed watched actor: $watched")
      senderActorRef ! ActorStopped(System.currentTimeMillis() - childStartedTime)
  }
}

case class MockActorSetupDetail(watcherEntityId: String, childEntityId: String) {
  def entityType: String = watcherEntityId + "Child"
}