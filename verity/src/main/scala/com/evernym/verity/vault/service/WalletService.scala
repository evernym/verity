package com.evernym.verity.vault.service

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

import com.evernym.verity.Exceptions.{BadRequestErrorException, HandledErrorException}
import com.evernym.verity.Status.INVALID_VALUE
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.actor.wallet.WalletCmdErrorResponse
import com.evernym.verity.constants.LogKeyConstants.LOG_KEY_ERR_MSG
import com.evernym.verity.logging.LoggingUtil
import com.evernym.verity.metrics.CustomMetrics.{AS_SERVICE_LIBINDY_WALLET_FAILED_COUNT, AS_SERVICE_LIBINDY_WALLET_SUCCEED_COUNT}
import com.evernym.verity.metrics.MetricsWriter
import com.typesafe.scalalogging.Logger
import kamon.Kamon
import kamon.metric.MeasurementUnit

import scala.concurrent.{Await, Future}
import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag


trait WalletService extends AsyncToSync {

  protected val logger: Logger = LoggingUtil.getLoggerByName("WalletService")

  /**
   * synchronous/BLOCKING wallet service call
   * soon to be DEPRECATED once all the wallet api caller code migrates to
   * asynchronous wallet service call
   *
   * @param walletId
   * @param cmd
   * @tparam T
   * @return
   */
  def executeSync[T: ClassTag](walletId: String, cmd: Any): T = {
    convertToSyncReq(executeAsync(walletId, cmd))
  }

  /**
   * asynchronous/non-blocking wallet service call
   * @param walletId
   * @param cmd
   * @return
   */
  def executeAsync[T: ClassTag](walletId: String, cmd: Any): Future[T] = {
    //TODO: find a better way to record metrics around future/async code block
    val startTime = Instant.now()
    execute(walletId, cmd).map {
      case wer: WalletCmdErrorResponse => //wallet service will/should return this in case of any error
        logger.error(s"error while executing wallet command: ${cmd.toString}, error msg: ${wer.sd.statusMsg}",
          (LOG_KEY_ERR_MSG, wer.sd.statusMsg))
        MetricsWriter.gaugeApi.increment(AS_SERVICE_LIBINDY_WALLET_FAILED_COUNT)
        wer.sd.statusCode match {
          case INVALID_VALUE.statusCode => throw new BadRequestErrorException(wer.sd.statusCode, Option(wer.sd.statusMsg))
          case _                        => throw HandledErrorException(wer.sd.statusCode, Option(wer.sd.statusMsg))
        }
      case r => r.asInstanceOf[T] //TODO: can we get rid of this .asInstanceOf method?
    }.map { resp =>
      val endTime = Instant.now()
      val seconds = ChronoUnit.SECONDS.between(startTime, endTime)
      Kamon
        .histogram("span_processing_time_seconds", MeasurementUnit.time.seconds)
        .withTag("operation", s"${cmd.getClass.getSimpleName}")
        .withTag("component", "WalletService")
        .record(seconds)
      MetricsWriter.gaugeApi.increment(AS_SERVICE_LIBINDY_WALLET_SUCCEED_COUNT)
      resp
    }
  }

  /**
   * actual wallet service implementation will implement this 'execute' function
   * @param walletId wallet identifier
   * @param cmd command
   * @return
   */
  protected def execute(walletId: String, cmd: Any): Future[Any]
}

trait AsyncToSync {

  def convertToSyncReq[T](fut: Future[T]): T = {
    //TODO: finalize timeout
    Await.result(fut, FiniteDuration(300, TimeUnit.SECONDS))
  }
}