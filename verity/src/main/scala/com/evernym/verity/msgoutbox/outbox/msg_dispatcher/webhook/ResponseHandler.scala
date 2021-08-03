package com.evernym.verity.msgoutbox.outbox.msg_dispatcher.webhook

import akka.Done
import akka.actor.typed.scaladsl.TimerScheduler
import com.evernym.verity.util2.Status.StatusDetail
import com.evernym.verity.msgoutbox.outbox.Outbox.Commands.{RecordFailedAttempt, RecordSuccessfulAttempt}
import com.evernym.verity.msgoutbox.outbox.msg_dispatcher.{DispatchParam, RetryParam}

object ResponseHandler {

  def handleResp[T](param: DispatchParam,
                    response: Either[StatusDetail, Done],
                    retryMsg: T)
                   (implicit timer: TimerScheduler[T]): Option[RetryParam] = {
    response match {
      case Right(_) =>
        param.replyTo ! RecordSuccessfulAttempt(param.msgId, param.comMethodId, sendAck = true, isItANotification = false)
        None

      case Left(sd: StatusDetail) =>
        val updatedRetryParam = param.retryParam.map(_.withFailedAttemptIncremented)
        val isAnyRetryAttemptsLeft = updatedRetryParam.exists(_.isRetryAttemptsLeft)

        param.replyTo ! RecordFailedAttempt(
          param.msgId,
          param.comMethodId,
          sendAck = ! isAnyRetryAttemptsLeft,
          isItANotification = false,
          isAnyRetryAttemptsLeft = isAnyRetryAttemptsLeft,
          sd
        )

        updatedRetryParam
          .filter(_.isRetryAttemptsLeft)
          .map { rp =>
            timer.startSingleTimer(param.msgId, retryMsg, rp.nextInterval)
            rp
          }
    }
  }
}
