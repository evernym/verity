package com.evernym.verity.actor.base

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, Timers}

import scala.concurrent.duration.Duration

trait HasActorTimers extends Timers { this: CoreActorExtended with Actor =>

  type JobId = String

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
    timers.cancel(jobId)
  }

  def stopAllScheduledJobs(): Unit = {
    timers.cancelAll()
  }
}
