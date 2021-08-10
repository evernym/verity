package com.evernym.verity.vault.service

import java.time.Instant
import java.time.temporal.ChronoUnit
import akka.actor.ActorRef
import com.evernym.verity.util2.Exceptions.{BadRequestErrorException, HandledErrorException}
import com.evernym.verity.util2.HasWalletExecutionContextProvider
import com.evernym.verity.util2.Status.{ALREADY_EXISTS, INVALID_VALUE, SIGNATURE_VERIF_FAILED}
import com.evernym.verity.actor.wallet.{WalletCmdErrorResponse, WalletCommand}
import com.evernym.verity.constants.LogKeyConstants.LOG_KEY_ERR_MSG
import com.evernym.verity.logging.LoggingUtil
import com.evernym.verity.metrics.CustomMetrics.{AS_SERVICE_LIBINDY_WALLET_FAILED_COUNT, AS_SERVICE_LIBINDY_WALLET_SUCCEED_COUNT}
import com.evernym.verity.metrics.{MetricsUnit, MetricsWriter}
import com.typesafe.scalalogging.Logger

import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag


trait WalletService extends HasWalletExecutionContextProvider {

  implicit def executionContext: ExecutionContext = futureWalletExecutionContext

  def metricsWriter : MetricsWriter

  protected val logger: Logger = LoggingUtil.getLoggerByName("WalletService")

  lazy val BAD_REQ_ERRORS = Set(INVALID_VALUE, SIGNATURE_VERIF_FAILED, ALREADY_EXISTS)

  def tell(walletId: String, cmd: WalletCommand)(implicit sender: ActorRef): Unit

  /**
   * asynchronous/non-blocking wallet service call
   * @param walletId
   * @param cmd
   * @return
   */
  def executeAsync[T: ClassTag](walletId: String, cmd: WalletCommand): Future[T] = {
    //TODO: find a better way to record metrics around future/async code block
    val startTime = Instant.now()
    logger.debug(s"[$walletId] [${cmd.id}] wallet service about to start execution of wallet cmd: ${cmd.name}")
    execute(walletId, cmd).map {
      case wer: WalletCmdErrorResponse => //wallet service will/should return this in case of any error
        metricsWriter.gaugeIncrement(AS_SERVICE_LIBINDY_WALLET_FAILED_COUNT)
        if (BAD_REQ_ERRORS.map(_.statusCode).contains(wer.sd.statusCode)) {
          throw new BadRequestErrorException(wer.sd.statusCode, Option(wer.sd.statusMsg))
        } else {
          logger.error(s"[$walletId] [${cmd.id}] error while executing wallet command: ${cmd.name}, error msg: ${wer.sd.statusMsg}",
            (LOG_KEY_ERR_MSG, wer.sd.statusMsg))
          throw HandledErrorException(wer.sd.statusCode, Option(wer.sd.statusMsg))
        }
      case r =>
        logger.debug(s"[$walletId] [${cmd.id}] wallet service received response: ${r.getClass.getSimpleName}")
        r.asInstanceOf[T]
    }.map { resp =>
      val endTime = Instant.now()
      val seconds = ChronoUnit.SECONDS.between(startTime, endTime)
      metricsWriter
        .histogramUpdate(
          "span_processing_time_seconds",
          MetricsUnit.TimeUnit.Seconds,
          seconds,
          tags = Map("operation"-> s"${cmd.name}", "component"-> "WalletService")
        )
      metricsWriter.gaugeIncrement(AS_SERVICE_LIBINDY_WALLET_SUCCEED_COUNT)
      logger.debug(s"[$walletId] [${cmd.id}] wallet service about to send response back: ${resp.getClass.getSimpleName}")
      resp
    }
  }

  /**
   * actual wallet service implementation will implement this 'execute' function
   * @param walletId wallet identifier
   * @param cmd command
   * @return
   */
  protected def execute(walletId: String, cmd: WalletCommand): Future[Any]
}