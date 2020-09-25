package com.evernym.verity.actor

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, Cancellable}
import com.evernym.verity.actor.persistence.ActorCommon
import com.typesafe.scalalogging.Logger

import scala.concurrent.duration.Duration
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.logging.LoggingUtil

trait HasActorMsgScheduler { this: ActorCommon with Actor =>

  private val logger: Logger = LoggingUtil.getLoggerByName("HasActorMsgScheduler")

  type JobId = String

  var scheduledJobs: Map[JobId, Cancellable] = Map.empty

  /**
   *
   * @param jobId caller needs to provide a unique id for the job
   * @param initialDelayInSeconds
   * @param intervalInSeconds
   * @param msg
   */
  def scheduleJob(jobId: JobId, initialDelayInSeconds: Int, intervalInSeconds: Int, msg: Any): Unit = {
    if (! scheduledJobs.contains(jobId) && initialDelayInSeconds >= 0) {
      val cancellable = context.system.scheduler.scheduleWithFixedDelay(
        Duration.create(initialDelayInSeconds, TimeUnit.SECONDS),
        Duration.create(intervalInSeconds, TimeUnit.SECONDS),
        self, msg)
      scheduledJobs += jobId -> cancellable
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
