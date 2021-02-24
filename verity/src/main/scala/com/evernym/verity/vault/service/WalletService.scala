package com.evernym.verity.vault.service

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

import com.evernym.verity.Exceptions.{BadRequestErrorException, HandledErrorException}
import com.evernym.verity.Status.{ALREADY_EXISTS, INVALID_VALUE, SIGNATURE_VERIF_FAILED}
import com.evernym.verity.ExecutionContextProvider.walletFutureExecutionContext
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


trait WalletService {

  protected val logger: Logger = LoggingUtil.getLoggerByName("WalletService")

  lazy val BAD_REQ_ERRORS = Set(INVALID_VALUE, SIGNATURE_VERIF_FAILED, ALREADY_EXISTS)

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
        MetricsWriter.gaugeApi.increment(AS_SERVICE_LIBINDY_WALLET_FAILED_COUNT)
        if (BAD_REQ_ERRORS.map(_.statusCode).contains(wer.sd.statusCode)) {
          throw new BadRequestErrorException(wer.sd.statusCode, Option(wer.sd.statusMsg))
        } else {
          logger.error(s"error while executing wallet command: ${cmd.getClass.getSimpleName}, error msg: ${wer.sd.statusMsg}",
            (LOG_KEY_ERR_MSG, wer.sd.statusMsg))
          throw HandledErrorException(wer.sd.statusCode, Option(wer.sd.statusMsg))
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
    Await.result(fut, FiniteDuration(250, TimeUnit.SECONDS))
  }
}