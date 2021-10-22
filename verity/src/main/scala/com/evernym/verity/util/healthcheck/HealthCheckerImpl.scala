package com.evernym.verity.util.healthcheck

import akka.actor.ActorSystem
import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, ReplyEffect}
import akka.util.Timeout
import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.actor.agent.AgentActorContext
import com.evernym.verity.actor.appStateManager.AppStateConstants.CONTEXT_AGENT_SERVICE_INIT
import com.evernym.verity.actor.appStateManager.{AppStateUpdateAPI, ErrorEvent, SeriousSystemError}
import com.evernym.verity.libindy.wallet.LibIndyWalletProvider
import com.evernym.verity.util.healthcheck.AkkaPersistenceStorageChecker.Commands.GetState
import com.evernym.verity.util.healthcheck.AkkaPersistenceStorageChecker.Replies.CurrentState
import com.evernym.verity.util.healthcheck.AkkaPersistenceStorageChecker.States.Ready
import com.evernym.verity.util2.Exceptions
import com.evernym.verity.util2.Exceptions.NoResponseFromLedgerPoolServiceException
import com.evernym.verity.vault.WalletDoesNotExist
import com.evernym.verity.vault.WalletUtil.generateWalletParamAsync

import java.util.UUID
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}

/**
 * Logic for this object based on com.evernym.verity.app_launcher.LaunchPreCheck methods
 */
class HealthCheckerImpl(val agentActorContext: AgentActorContext,
                        val actorSystem: ActorSystem,
                        implicit val futureExecutionContext: ExecutionContext)
  extends HealthChecker {
  import akka.actor.typed.ActorSystem
  import akka.actor.typed.scaladsl.adapter._
  import akka.actor.typed.scaladsl.AskPattern._

  implicit val typedSystem: ActorSystem[_] = actorSystem.toTyped

  override def checkAkkaStorageStatus: Future[ApiStatus] = {
    implicit val timeout: Timeout = Timeout(Duration.create(15, TimeUnit.SECONDS))
    val actorId = "dummy-actor-" + UUID.randomUUID().toString
    val checker = typedSystem.toClassic.spawn(AkkaPersistenceStorageChecker(actorId), actorId)
    checker
      .ask(ref => GetState(ref))
      .map {
        case CurrentState(Ready) => ApiStatus(status = true, "OK")
        case _                   => ApiStatus(status = false, "check akka persistence storage failed")
      }.recover {
        case e: RuntimeException => ApiStatus(status = false, e.getMessage)
      }
  }

  override def checkWalletStorageStatus: Future[ApiStatus] = {
    val walletId = "dummy-wallet-" + UUID.randomUUID().toString
    val wap = generateWalletParamAsync(walletId,agentActorContext.appConfig, LibIndyWalletProvider)
    wap.flatMap{ w =>
      LibIndyWalletProvider.openAsync(w.walletName, w.encryptionKey, w.walletConfig)
    }.map { _ =>
      ApiStatus(status = true, "OK")
    }.recover {
      case _: WalletDoesNotExist => ApiStatus(status = true, "OK")
      case e: Exception => ApiStatus(status = false, e.getMessage)
    }
  }

  override def checkBlobStorageStatus: Future[ApiStatus] = {
    agentActorContext
      .storageAPI
      .ping
      .map { _ =>
        ApiStatus(status = true, "OK")
      }.recover {
        case e: Exception => ApiStatus(status = false, e.getMessage)
      }
  }

  override def checkLedgerPoolStatus: Future[ApiStatus] = {
    Future(agentActorContext.poolConnManager.open())
      .map { _ =>
        ApiStatus(status = true, "OK")
      }.recover {
        case e: Exception =>
          val errorMsg = s"ledger connection check failed (error stack trace: ${Exceptions.getStackTraceAsSingleLineString(e)})"
          AppStateUpdateAPI(actorSystem).publishEvent(ErrorEvent(SeriousSystemError, CONTEXT_AGENT_SERVICE_INIT,
            new NoResponseFromLedgerPoolServiceException(Option(errorMsg))))
          ApiStatus(status = false, e.getMessage)
      }
  }

  //This method checks that Verity can respond to the liveness request,
  // and `Future{}` checks if ExecutionContext is available, and can execute Future.
  override def checkLiveness: Future[Unit] = {
    Future {}
  }
}


object AkkaPersistenceStorageChecker {
  trait Cmd extends ActorMessage
  object Commands {
    case class GetState(reply: ActorRef[Reply]) extends Cmd
  }
  trait Event
  trait State
  object States {
    object Ready extends State
  }

  trait Reply extends ActorMessage
  object Replies {
    case class CurrentState(st: State) extends Reply
  }

  def apply(entityId: String): Behavior[Cmd] = {
    Behaviors.setup { _ =>
      EventSourcedBehavior
        .withEnforcedReplies(
          PersistenceId("DummyActor", entityId),
          States.Ready,
          commandHandler,
          eventHandler
        )
    }
  }

  private def commandHandler: (State, Cmd) => ReplyEffect[Event, State] = {
    case (st: State, GetState(replyTo)) =>
      Effect
        .stop()
        .thenReply(replyTo)( _ => CurrentState(st))
  }

  private def eventHandler: (State, Event) => State = { case (state, event) =>
    state
  }
}