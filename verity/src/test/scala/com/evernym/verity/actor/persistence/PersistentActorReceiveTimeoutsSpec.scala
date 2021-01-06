package com.evernym.verity.actor.persistence

import java.util.concurrent.TimeoutException

import akka.actor.{Actor, ActorRef, ActorSystem, Props, Terminated}
import akka.util.Timeout
import akka.pattern.ask
import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.actor.testkit.{AkkaTestBasic, TestAppConfig}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.CommonConfig._
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.evernym.verity.testkit.BasicSpec
import com.typesafe.config.{Config, ConfigFactory, ConfigValueFactory}
import com.typesafe.scalalogging.Logger

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._


class PersistentActorReceiveTimeoutsSpec extends BasicSpec {
  import TestActor._

  val logger: Logger = getLoggerByClass(classOf[PersistentActorReceiveTimeoutsSpec])

  implicit val timeout: Timeout = Timeout(15.seconds)
  implicit val executionContext: ExecutionContext = ExecutionContext.Implicits.global

  implicit val system: ActorSystem = AkkaTestBasic.system()

  timeoutsSpec()

  def timeoutsSpec(): Unit = {
    val entityName = "user"

    "ActorShutdownTests" - {
      "BasePersistentActor" - {

        "When no timeout configuration present" - {
          "should follow hardcoded timeout" in {
            val entityId = s"BaseChild-$nextActorId"
            val conf = ConfigFactory.load()

            checkPersistentActor(DEFAULT_RECEIVE_TIMEOUT.seconds, WatchBaseActor(entityId), conf)
          }
        }

        "When configured only category default" - {
          "should use category default timeout" in {
            val entityId = s"BaseChild-$nextActorId"
            val conf: Config = ConfigFactory.load()
              .withValue(PERSISTENT_ACTOR_BASE_RECEIVE_TIMEOUT_SECONDS, ConfigValueFactory.fromAnyRef(5))

            checkPersistentActor(5.seconds, WatchBaseActor(entityId), conf)
          }
        }

        "When configured only category default set to 0" - {
          "should not terminate actor (indefinite timeout)" in {
            val entityId = s"BaseChild-$nextActorId"
            val conf: Config = ConfigFactory.load()
              .withValue(PERSISTENT_ACTOR_BASE_RECEIVE_TIMEOUT_SECONDS, ConfigValueFactory.fromAnyRef(0))

            checkPersistentActor(Duration.Undefined, WatchBaseActor(entityId), conf)
          }
        }

        "When configured category and entityName timeouts" - {
          "should use entityName timeout" in {
            val entityId = s"BaseChild-$nextActorId"
            val conf: Config = ConfigFactory.load()
              .withValue(PERSISTENT_ACTOR_BASE_RECEIVE_TIMEOUT_SECONDS, ConfigValueFactory.fromAnyRef(3))
              .withValue(s"$PERSISTENT_ACTOR_BASE.$entityName.$RECEIVE_TIMEOUT_SECONDS",
                ConfigValueFactory.fromAnyRef(5))

            checkPersistentActor(5.seconds, WatchBaseActor(entityId), conf)
          }
        }

        "When configured category and entityName timeouts, with entityName timeout set to 0" - {
          "should not terminate actor (indefinite timeout)" in {
            val entityId = s"BaseChild-$nextActorId"
            val conf: Config = ConfigFactory.load()
              .withValue(PERSISTENT_ACTOR_BASE_RECEIVE_TIMEOUT_SECONDS, ConfigValueFactory.fromAnyRef(3))
              .withValue(s"$PERSISTENT_ACTOR_BASE.$entityName.$RECEIVE_TIMEOUT_SECONDS",
                ConfigValueFactory.fromAnyRef(0))

            checkPersistentActor(Duration.Undefined, WatchBaseActor(entityId), conf)
          }
        }

        "When configured category, entityName and entityId timeouts" - {
          "should use entityId timeout" in {
            val entityId = s"BaseChild-$nextActorId"
            val conf: Config = ConfigFactory.load()
              .withValue(PERSISTENT_ACTOR_BASE_RECEIVE_TIMEOUT_SECONDS, ConfigValueFactory.fromAnyRef(2))
              .withValue(s"$PERSISTENT_ACTOR_BASE.$entityName.$RECEIVE_TIMEOUT_SECONDS", ConfigValueFactory.fromAnyRef(7))
              .withValue(s"$PERSISTENT_ACTOR_BASE.$entityName.$entityId.$RECEIVE_TIMEOUT_SECONDS", ConfigValueFactory.fromAnyRef(5))

            checkPersistentActor(5.seconds, WatchBaseActor(entityId), conf)
          }
        }

        "When configured category, entityName and entityId timeouts, with entityId timeout set to 0" - {
          "should not terminate actor (indefinite timeout)" in {
            val entityId = s"BaseChild-$nextActorId"
            val conf: Config = ConfigFactory.load()
              .withValue(PERSISTENT_ACTOR_BASE_RECEIVE_TIMEOUT_SECONDS, ConfigValueFactory.fromAnyRef(2))
              .withValue(s"$PERSISTENT_ACTOR_BASE.$entityName.$RECEIVE_TIMEOUT_SECONDS", ConfigValueFactory.fromAnyRef(7))
              .withValue(s"$PERSISTENT_ACTOR_BASE.$entityName.$entityId.$RECEIVE_TIMEOUT_SECONDS", ConfigValueFactory.fromAnyRef(0))

            checkPersistentActor(Duration.Undefined, WatchBaseActor(entityId), conf)
          }
        }
      }
      "SingletonChildrenPersistentActor" - {
        "When not configured" - {
          "should follow hardcoded timeout" in {
            val entityId = s"SingletonChild-$nextActorId"
            val conf = ConfigFactory.load()

            checkPersistentActor(DEFAULT_RECEIVE_TIMEOUT.seconds, WatchSingletonChildActor(entityId), conf)
          }
        }

        "When configured only category default" - {
          "should use category default timeout" in {
            val entityId = s"SingletonChild-$nextActorId"
            val conf: Config = ConfigFactory.load()
              .withValue(PERSISTENT_SINGLETON_CHILDREN_RECEIVE_TIMEOUT_SECONDS, ConfigValueFactory.fromAnyRef(5))

            checkPersistentActor(5.seconds, WatchSingletonChildActor(entityId), conf)
          }
        }

        "When configured only category default set to 0" - {
          "should not terminate actor (indefinite timeout)" in {
            val entityId = s"SingletonChild-$nextActorId"
            val conf: Config = ConfigFactory.load()
              .withValue(PERSISTENT_SINGLETON_CHILDREN_RECEIVE_TIMEOUT_SECONDS, ConfigValueFactory.fromAnyRef(5))

            checkPersistentActor(5.seconds, WatchSingletonChildActor(entityId), conf)
          }
        }

        "When configured category and entityName timeouts" - {
          "should use entityName timeout" in {
            val entityId = s"SingletonChild-$nextActorId"
            val conf: Config = ConfigFactory.load()
              .withValue(PERSISTENT_SINGLETON_CHILDREN_RECEIVE_TIMEOUT_SECONDS, ConfigValueFactory.fromAnyRef(3))
              .withValue(s"$PERSISTENT_SINGLETON_CHILDREN.$entityName.$RECEIVE_TIMEOUT_SECONDS",
                ConfigValueFactory.fromAnyRef(5))

            checkPersistentActor(5.seconds, WatchSingletonChildActor(entityId), conf)
          }
        }

        "When configured category and entityName timeouts, with entityName timeout set to 0" - {
          "should not terminate actor (indefinite timeout)" in {
            val entityId = s"SingletonChild-$nextActorId"
            val conf: Config = ConfigFactory.load()
              .withValue(PERSISTENT_SINGLETON_CHILDREN_RECEIVE_TIMEOUT_SECONDS, ConfigValueFactory.fromAnyRef(3))
              .withValue(s"$PERSISTENT_SINGLETON_CHILDREN.$entityName.$RECEIVE_TIMEOUT_SECONDS",
                ConfigValueFactory.fromAnyRef(0))

            checkPersistentActor(Duration.Undefined, WatchSingletonChildActor(entityId), conf)
          }
        }

        "When configured category, entityName and entityId timeouts" - {
          "should use entityId timeout" in {
            val entityId = s"SingletonChild-$nextActorId"
            val conf: Config = ConfigFactory.load()
              .withValue(PERSISTENT_SINGLETON_CHILDREN_RECEIVE_TIMEOUT_SECONDS, ConfigValueFactory.fromAnyRef(2))
              .withValue(s"$PERSISTENT_SINGLETON_CHILDREN.$entityName.$RECEIVE_TIMEOUT_SECONDS",
                ConfigValueFactory.fromAnyRef(7))
              .withValue(s"$PERSISTENT_SINGLETON_CHILDREN.$entityName.$entityId.$RECEIVE_TIMEOUT_SECONDS",
                ConfigValueFactory.fromAnyRef(5))

            checkPersistentActor(5.seconds, WatchSingletonChildActor(entityId), conf)
          }
        }

        "When configured category, entityName and entityId timeouts, with entityId timeout set to 0" - {
          "should not terminate actor (indefinite timeout)" in {
            val entityId = s"SingletonChild-$nextActorId"
            val conf: Config = ConfigFactory.load()
              .withValue(PERSISTENT_SINGLETON_CHILDREN_RECEIVE_TIMEOUT_SECONDS, ConfigValueFactory.fromAnyRef(2))
              .withValue(s"$PERSISTENT_SINGLETON_CHILDREN.$entityName.$RECEIVE_TIMEOUT_SECONDS",
                ConfigValueFactory.fromAnyRef(7))
              .withValue(s"$PERSISTENT_SINGLETON_CHILDREN.$entityName.$entityId.$RECEIVE_TIMEOUT_SECONDS",
                ConfigValueFactory.fromAnyRef(0))

            checkPersistentActor(Duration.Undefined, WatchSingletonChildActor(entityId), conf)
          }
        }
      }
    }
  }

  def checkPersistentActor(expectedTimeout: Duration, msg: ActorMessage, conf: Config): Unit = {
    val appConfig = new TestAppConfig(Option(conf))
    val watcher = system.actorOf(Props(new WatcherActor(appConfig, expectedTimeout)), name = s"Watcher-$nextActorId")
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

object TestActor {
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

import TestActor._


class TestBaseActor(val appConfig: AppConfig) extends BasePersistentActor {

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
}

class TestSingletonChildActor(val appConfig: AppConfig) extends SingletonChildrenPersistentActor {

  override def receiveCmd: Receive = {
    case ReceiveTimeoutQuestion() =>
      logger.info(s"$self: my timeout is: $entityReceiveTimeout")
      sender() ! ReceiveTimeoutAnswer(entityReceiveTimeout)
    case x => logger.info(s"receiveCmd: $x")
  }

  override def receiveEvent: Receive = {
    case x => logger.info(s"receiveEvent: $x")
  }

  override def persistenceEncryptionKey: String = "klsd89894kdsjisdji4"

  override val defaultReceiveTimeoutInSeconds: Int = DEFAULT_RECEIVE_TIMEOUT
}

class WatcherActor(appConfig: AppConfig, expectedTimeout: Duration) extends Actor {
  val logger: Logger = getLoggerByClass(classOf[WatcherActor])
  var testRef: ActorRef = _
  var persistStart: Long = _

  override def receive: Receive = {
    case WatchBaseActor(name) =>
      testRef = sender()
      val child: ActorRef = context.actorOf(Props(new TestBaseActor(appConfig)), name = name)
      context.watch(child)
      child ! ReceiveTimeoutQuestion()
      persistStart = System.currentTimeMillis()

    case WatchSingletonChildActor(name) =>
      testRef = sender()
      val child: ActorRef = context.actorOf(Props(new TestSingletonChildActor(appConfig)), name = name)
      context.watch(child)
      child ! ReceiveTimeoutQuestion()
      persistStart = System.currentTimeMillis()

    case ReceiveTimeoutAnswer(timeout) =>
      logger.info(s"${sender()} is alive and its timeout is: $timeout")

      if (timeout.isFinite() && timeout != expectedTimeout)
        testRef ! WrongTimeoutReported(timeout)

    case Terminated(kenny) =>
      logger.info(s"OMG, they killed Kenny: $kenny")
      testRef ! ActorStopped(System.currentTimeMillis() - persistStart)
  }
}
