package com.evernym.verity.actor

import akka.Done
import akka.actor.{ActorRef, ActorSystem}
import akka.cluster.Cluster
import com.evernym.verity.actor.appStateManager.StartDraining
import com.evernym.verity.config.AppConfig
import com.evernym.verity.constants.LogKeyConstants.LOG_KEY_ERR_MSG
import com.evernym.verity.http.common.StatusDetailResp
import com.evernym.verity.observability.logs.LoggingUtil
import com.typesafe.scalalogging.Logger

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration._
import scala.util.Success


class AppStateHandler(appConfig: AppConfig,
                      system: ActorSystem,
                      appStateManager: ActorRef)
                     (implicit val ex: ExecutionContext) {

  def isDrainingStarted: Boolean = _isDrainingStarted

  def currentStatus: StatusDetailResp = {
    import com.evernym.verity.util2.Status.{ACCEPTING_TRAFFIC, NOT_ACCEPTING_TRAFFIC}
    if (isDrainingStarted) {
      StatusDetailResp(NOT_ACCEPTING_TRAFFIC.withMessage("draining started"))
    } else {
      StatusDetailResp(ACCEPTING_TRAFFIC.withMessage("Listening"))
    }
  }

  def startScheduledCoordinatedShutdown(): Future[Done] = {
    _isDrainingStarted = true
    legacyStartDraining()
    logger.info("Trapping SIGTERM and begin draining Akka node...")
    val promise = Promise[Done]()
    scheduleCoordinatedShutdown(promise)
    promise.future
  }

  //need to keep this till we support both AppStateManager
  private def legacyStartDraining(): Unit = {
    appStateManager ! StartDraining
  }

  private def scheduleCoordinatedShutdown(promise: Promise[Done]): Unit = {
    val drainingPeriod =
      appConfig
        .getDurationReq("akka.coordinated-shutdown.phases.before-service-unbind.timeout")
        //below is to make sure it waits little less that the `default-phase-timeout`
        // else the coordinated shutdown will fail with timeout error
        .minus(5.seconds)

    logger.info(
      s"""will remain in draining state for at least $drainingPeriod
         | before starting the Coordinated Shutdown...""".stripMargin)
    system
      .scheduler
      .scheduleOnce(drainingPeriod) {
        try {
          promise.complete(Success(Done))
          //start coordinated shutdown (as mentioned here: https://doc.akka.io/docs/akka/current/coordinated-shutdown.html#coordinated-shutdown)
          val cluster = Cluster(system)
          logger.info(s"akka node ${cluster.selfAddress} will start coordinated shutdown...")
          val terminationStartTime = LocalDateTime.now()
          system
            .terminate()
            .map { _ =>
              cluster.down(cluster.selfAddress) //TODO: need to confirm if this is required or not
              val terminationFinishTime = LocalDateTime.now()
              val timeTaken = ChronoUnit.MILLIS.between(terminationStartTime, terminationFinishTime)
              logger.info(s"coordinated shutdown finished in millis: $timeTaken")
            }.recover {
            case e: Throwable =>
              logger.error("error encountered during coordinated shutdown", (LOG_KEY_ERR_MSG, e))
          }
        } catch {
          case e: Exception =>
            logger.error(s"error during coordinated shutdown process: ${e.toString}")
        }
      }
  }

  private var _isDrainingStarted = false
  val logger: Logger = LoggingUtil.getLoggerByClass(getClass)
}