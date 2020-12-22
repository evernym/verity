package com.evernym.verity.vault.service

import java.time.temporal.ChronoUnit
import java.time.Instant
import java.util.concurrent.TimeUnit

import akka.util.Timeout
import com.evernym.verity.Exceptions.{BadRequestErrorException, HandledErrorException}
import com.evernym.verity.Status.INVALID_VALUE
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.actor.wallet.WalletCmdErrorResponse
import kamon.Kamon
import kamon.metric.MeasurementUnit

import scala.concurrent.{Await, Future}
import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag


trait WalletService extends AsyncToSync {

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
        wer.sd.statusCode match {
          case INVALID_VALUE.statusCode => throw new BadRequestErrorException(wer.sd.statusCode, Option(wer.sd.statusMsg))
          case _ => throw HandledErrorException(wer.sd.statusCode, Option(wer.sd.statusMsg))
        }
      case r => r.asInstanceOf[T] //TODO: can we get rid of this .asInstanceOf method?
    }.map { resp =>
      val endTime = Instant.now()
      val seconds = ChronoUnit.SECONDS.between(startTime, endTime)
      Kamon
        .histogram("span_processing_time_seconds", MeasurementUnit.time.seconds)
        .withTag("operation", s"wallet cmd: ${cmd.getClass.getSimpleName}")
        .withTag("component", "WalletService")
        .record(seconds)
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
  //TODO: finalize the wallet service timeout
  //TODO: when this timeout was set around 15-25 seconds,
  // the 'write-def' protocol was failing during sdk flow test, should find out why and fix it.
  val WALLET_SERVICE_TIMEOUT: FiniteDuration = FiniteDuration(50, TimeUnit.SECONDS)
  implicit val defaultTimeout: Timeout = Timeout(WALLET_SERVICE_TIMEOUT)

  def convertToSyncReq[T](fut: Future[T]): T = {
    Await.result(fut, WALLET_SERVICE_TIMEOUT)
  }
}