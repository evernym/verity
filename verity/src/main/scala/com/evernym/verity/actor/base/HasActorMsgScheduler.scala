package com.evernym.verity.actor.base

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, Cancellable, Timers}
import com.evernym.verity.logging.LoggingUtil
import com.typesafe.scalalogging.Logger

import scala.concurrent.duration.Duration

trait HasActorMsgScheduler extends Timers { this: ActorBase with Actor =>

  private val logger: Logger = LoggingUtil.getLoggerByName("HasActorMsgScheduler")

  type JobId = String

  var scheduledJobs: Map[JobId, Cancellable] = Map.empty

  /**
   *
   * @param jobId caller needs to provide a unique id for the job
   * @param intervalInSeconds
   * @param msg
   */
  def scheduleJob(jobId: JobId, intervalInSeconds: Int, msg: Any): Unit = {
    if (intervalInSeconds > 0) {
      timers.startTimerWithFixedDelay(
        jobId,
        msg,
        Duration.create(intervalInSeconds, TimeUnit.SECONDS)
      )
    }
  }

  def stopScheduledJob(jobId: JobId): Unit = {
    scheduledJobs.get(jobId).foreach { job =>
      try {
        job.cancel()
        scheduledJobs -= jobId
      } catch {
        case e: RuntimeException =>
          logger.debug(s"[$actorId] scheduled job cancellation failed: " + e.getLocalizedMessage)
      }
    }
  }

  def stopAllScheduledJobs(): Unit = {
    scheduledJobs.keySet.foreach(stopScheduledJob)
  }
}
