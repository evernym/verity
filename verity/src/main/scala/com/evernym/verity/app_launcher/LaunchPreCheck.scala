package com.evernym.verity.app_launcher
import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByClass
import com.evernym.verity.util.healthcheck.HealthChecker

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}


case class StartupProbeStatus(status: Boolean,
                              akkaStorageStatus: String,
                              walletStorageStatus: String,
                              blobStorageStatus: String,
                              ledgerPoolStatus: String)

/**
 * checks to perform during agent service start to make sure that basic required
 * external dependencies are available/responding (like DynamoDB, MySql, Ledger etc)
 */
object LaunchPreCheck {

  private val logger = getLoggerByClass(getClass)

  def waitForRequiredDepsIsOk(healthChecker: HealthChecker, executionContext: ExecutionContext): Unit = {
    while (true) {
      val result = Await.result(startupProbe(healthChecker)(executionContext), 10.seconds)
      if (result.status) {
        logger.info("Successfully check external deps")
        return // all are ok if this line executed
      }
      logger.warn("Startup probe check returned 'not ready': \n" + result.toString)
    }
  }

  private def startupProbe(healthChecker: HealthChecker)
                          (implicit executionContext: ExecutionContext): Future[StartupProbeStatus] = {
    val akkaStorageFuture = healthChecker.checkAkkaStorageStatus
    val walletStorageFuture = healthChecker.checkWalletStorageStatus
    val blobStorageFuture = healthChecker.checkBlobStorageStatus
    val ledgerFuture = healthChecker.checkLedgerPoolStatus
    for {
      akkaStorage   <- akkaStorageFuture
      walletStorage <- walletStorageFuture
      blobStorage   <- blobStorageFuture
      ledger        <- ledgerFuture
    } yield StartupProbeStatus(
      akkaStorage.status && walletStorage.status && blobStorage.status && ledger.status,
      akkaStorage.msg,
      walletStorage.msg,
      blobStorage.msg,
      ledger.msg
    )
  }

}