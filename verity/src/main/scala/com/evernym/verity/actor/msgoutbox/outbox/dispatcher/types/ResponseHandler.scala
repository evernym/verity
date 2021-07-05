package com.evernym.verity.actor.msgoutbox.outbox.dispatcher.types

import akka.Done
import akka.actor.typed.scaladsl.TimerScheduler
import com.evernym.verity.Status.StatusDetail
import com.evernym.verity.actor.msgoutbox.outbox.Outbox.Commands.{RecordFailedAttempt, RecordSuccessfulAttempt}
import com.evernym.verity.actor.msgoutbox.outbox.RetryParam

object ResponseHandler {

  def handleResp[T](param: BaseDispatchParam,
                    response: Either[StatusDetail, Done],
                    retryMsg: T)
                   (implicit timer: TimerScheduler[T]): Option[RetryParam] = {
    response match {
      case Right(_) =>
        param.replyTo ! RecordSuccessfulAttempt(param.msgId, param.comMethodId, isItANotification = false)
        None

      case Left(sd: StatusDetail) =>
        param.replyTo ! RecordFailedAttempt(
          param.msgId,
          param.comMethodId,
          isItANotification = false,
          isAnyRetryAttemptsLeft = param.retryParam.exists(_.isRetryAttemptsLeft),
          sd
        )
        param.retryParam
          .map(_.withFailedAttemptIncremented)
          .filter(_.isRetryAttemptsLeft).map { rp =>
            timer.startSingleTimer(param.msgId, retryMsg, rp.nextInterval)
            rp
        }
    }
  }
}
