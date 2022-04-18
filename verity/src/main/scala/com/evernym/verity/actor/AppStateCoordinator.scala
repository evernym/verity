package com.evernym.verity.actor

import akka.Done
import akka.actor.{ActorSystem, CoordinatedShutdown}
import akka.actor.CoordinatedShutdown._
import com.evernym.verity.actor.appStateManager.StartDraining
import com.evernym.verity.config.AppConfig
import com.evernym.verity.observability.logs.LoggingUtil
import com.typesafe.scalalogging.Logger

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.Success
import scala.concurrent.duration._

class AppStateCoordinator(appConfig: AppConfig,
                          system: ActorSystem,
                          platform: Platform)
                         (implicit val ex: ExecutionContext) {

  def isDrainingStarted: Boolean = _isDrainingStarted.get()

  def setDrainingStarted(): Unit = {
    _isDrainingStarted.set(true)
  }

  def incrementPostDrainingReadinessProbeCount(): Unit = {
    if (_isDrainingStarted.get())
      _postDrainingReadinessProbeCount.incrementAndGet()
  }

  def startDrainingProcess(): Future[Done] = {
    setDrainingStarted()

    val maxCheckCount = appConfig
      .getIntOption("verity.draining.max-check-count")
      .getOrElse(10)

    val checkInterval = appConfig
      .getDurationOption("verity.draining.check-interval")
      .getOrElse(Duration(2, SECONDS))

    legacyStartDraining()
    preDraining().flatMap { _ =>
      performDraining(maxCheckCount, checkInterval, Promise[Done]())
    }
  }

  //need to keep this till we support 'AppStateManager' and 'new probe APIs'
  private def legacyStartDraining(): Unit = {
    platform.appStateManager ! StartDraining
  }

  private def preDraining(): Future[Done] = {
    val fut1 = platform.eventConsumerAdapter.stop()
    val fut2 = platform.basicEventStore.map(_.stop()).getOrElse(Future.successful(Done))
    for (
      _ <- fut1;
      _ <- fut2
    ) yield {
      Done
    }
  }

  private def performDraining(checkAttemptLeft: Int,
                              checkInterval: FiniteDuration,
                              promise: Promise[Done]): Future[Done] = {
    if (checkAttemptLeft < 1 || _postDrainingReadinessProbeCount.get() >= 1) {
      //this means
      // - either "max check attempt exceeded" (in this case any new received request may/will fail)
      // - or "now LB already knows about the draining state"
      // let this node process requests received so far
      val waitInterval = appConfig
        .getDurationOption("verity.draining.wait-before-service-unbind")
        .getOrElse(Duration(10, SECONDS))

      system
        .scheduler
        .scheduleOnce(waitInterval) {
          //waited for sufficient time to let the node process existing received requests
          // and so now let the coordinated shutdown phase continue by completing the promise

          // the moment below promise completes the future,
          // the 'service-unbind' phase will continue
          // which means any new probe/heartbeat api should start failing (502 etc)
          promise.complete(Success(Done))
        }
    } else {
      //the 'draining started' state is not yet communicated/known to the LB and
      // hence `wait` for next readinessProbe(new)/heartbeat(legacy) api call
      // to confirm that the 'draining state' has been communicated/known by the LB
      system
        .scheduler
        .scheduleOnce(checkInterval) {
          performDraining(checkAttemptLeft-1, checkInterval, promise)
        }
    }
    promise.future
  }

  private def addTasksToCoordinatedShutdownPhases(): Unit = {
    addTaskToBeforeServiceUnbindPhase()
    addLogDuringPhase(PhaseBeforeServiceUnbind, "log-before-service-unbind")
    addLogDuringPhase(PhaseServiceStop, "log-service-stop")
    addLogDuringPhase(PhaseClusterLeave, "log-cluster-leave")
    addLogDuringPhase(PhaseClusterShutdown, "log-cluster-shutdown")
    addLogDuringPhase(PhaseActorSystemTerminate, "log-actor-system-terminate")
  }

  private def addTaskToBeforeServiceUnbindPhase(): Unit = {
    CoordinatedShutdown(system)
      .addTask(PhaseBeforeServiceUnbind, "start-draining-process") { () =>
        logger.info("Coordinated shutdown [BeforeServiceUnbind-draining]")
        //will wait for `akka.coordinated-shutdown.phases.before-service-unbind.timeout`
        // to complete the future returned by below statement
        startDrainingProcess()
      }
  }

  private def addLogDuringPhase(phase: String, taskName: String): Unit = {
    CoordinatedShutdown(system)
      .addTask(phase, taskName) { () =>
        logger.info(s"Coordinated shutdown [$phase:$taskName]")
        Future.successful(Done)
      }
  }

  private val _isDrainingStarted = new AtomicBoolean(false)
  private val _postDrainingReadinessProbeCount = new AtomicInteger(0)

  val logger: Logger = LoggingUtil.getLoggerByClass(getClass)


  //Verity will start coordinated shutdown when any of these happens:
  //  - `systemd` sends the JVM a SIGTERM during 'systemctl stop'
  //  - `kubernetes` sends the Container a SIGTERM during 'pod termination'

  //Below function adds some tasks to be executed during coordinated shutdown
  // https://doc.akka.io/docs/akka/current/coordinated-shutdown.html
  addTasksToCoordinatedShutdownPhases()
}