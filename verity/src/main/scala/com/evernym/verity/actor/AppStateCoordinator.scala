package com.evernym.verity.actor

import akka.Done
import akka.actor.{ActorRef, ActorSystem}
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
                          appStateManager: ActorRef)
                         (implicit val ex: ExecutionContext) {

  def isDrainingStarted: Boolean = _isDrainingStarted.get()

  def setDrainingStarted(): Unit = {
    _isDrainingStarted.set(true)
  }

  def incrementPostDrainingReadinessProbeCount(): Unit = {
    if (_isDrainingStarted.get())
      _postDrainingReadinessProbeCount.incrementAndGet()
  }

  def startBeforeServiceUnbindTask(): Future[Done] = {
    val checkInterval = appConfig
      .getDurationOption("verity.draining.check-interval")
      .getOrElse(Duration(2, SECONDS))

    val maxCheckCount = appConfig
      .getIntOption("verity.draining.max-check-count")
      .getOrElse(10)

    legacyStartDraining()
    performDraining(checkInterval, maxCheckCount, Promise[Done]())
  }

  //need to keep this till we support both AppStateManager
  private def legacyStartDraining(): Unit = {
    appStateManager ! StartDraining
  }

  private def performDraining(checkInterval: FiniteDuration,
                              checkAttemptLeft: Int,
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
          performDraining(checkInterval, checkAttemptLeft-1, promise)
        }
    }
    promise.future
  }

  private val _isDrainingStarted = new AtomicBoolean(false)
  private val _postDrainingReadinessProbeCount = new AtomicInteger(0)

  val logger: Logger = LoggingUtil.getLoggerByClass(getClass)
}