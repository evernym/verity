package com.evernym.verity.app_launcher
import akka.actor.ActorSystem
import akka.pattern.after
import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByClass
import com.evernym.verity.util.healthcheck.{ApiStatus, HealthChecker}

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{Await, ExecutionContext, Future, Promise, TimeoutException}
import scala.util.{Failure, Success}


case class StartupProbeStatus(status: Boolean,
                              akkaStorageStatus: String,
                              walletStorageStatus: String,
                              blobStorageStatus: String,
                              ledgerPoolStatus: String,
                              vdrToolsStatus: String)

/**
 * checks to perform during agent service start to make sure that basic required
 * external dependencies are available/responding (like DynamoDB, MySql, Ledger etc)
 */
object LaunchPreCheck {

  private val logger = getLoggerByClass(getClass)

  def waitForRequiredDepsIsOk(healthChecker: HealthChecker,
                              system: ActorSystem,
                              executionContext: ExecutionContext): Unit = {
    logger.info("Startup probe check started")
    val startupProbeFut = startupProbe(healthChecker, system)(executionContext)
    do {
      try {
        val result = Await.result(startupProbeFut, 10.seconds)
        if (result.status) {
          logger.info("Startup probe check successful")
          return
        } else {
          logger.info("Startup probe check not successful: \n" + result.toString)
        }
      } catch {
        case e @ (_: InterruptedException | _: TimeoutException) =>
          logger.info("Startup probe check interrupted/timedout (will be re-checked): " + e.getMessage)
        case e: RuntimeException =>
          logger.warn("Startup probe check unsuccessful (will be re-checked): " + e.getMessage)
      }
    } while (true)
  }

  private def startupProbe(healthChecker: HealthChecker,
                           system: ActorSystem)
                          (implicit executionContext: ExecutionContext): Future[StartupProbeStatus] = {
    val akkaStorageFuture = checkApiStatus(system, "akka persistence", {healthChecker.checkAkkaStorageStatus})
    val walletStorageFuture = checkApiStatus(system, "wallet storage", {healthChecker.checkWalletStorageStatus})
    val blobStorageFuture = checkApiStatus(system, "blob storage", {healthChecker.checkBlobStorageStatus})
    val ledgerFuture = checkApiStatus(system, "ledger", {healthChecker.checkLedgerPoolStatus})
    val vdrFuture = checkApiStatus(system, "vdrtools", {healthChecker.checkVDRToolsStatus})
    for {
      akkaStorage   <- akkaStorageFuture
      walletStorage <- walletStorageFuture
      blobStorage   <- blobStorageFuture
      ledger        <- ledgerFuture
      vdrTools      <- vdrFuture
    } yield StartupProbeStatus(
      akkaStorage.status && walletStorage.status && blobStorage.status && ledger.status && vdrTools.status,
      akkaStorage.msg,
      walletStorage.msg,
      blobStorage.msg,
      ledger.msg,
      vdrTools.msg
    )
  }

  private def checkApiStatus(system: ActorSystem,
                             checkName: String,
                             checkDependency: => Future[ApiStatus],
                             checkInterval: FiniteDuration = 1.seconds,
                             promise: Promise[ApiStatus] = Promise[ApiStatus])
                            (implicit executionContext: ExecutionContext): Future[ApiStatus] = {

    def retryCheck(waitInterval: FiniteDuration = 0.seconds): Future[ApiStatus] = {
      after(waitInterval, system.scheduler) {
        val nextInterval = checkInterval.plus(500.millis)
        checkApiStatus(system, checkName, checkDependency, nextInterval, promise)
      }
    }

    //execute the dependency check and process result accordingly
    val delayedFuture = after(checkInterval, system.scheduler)(Future.failed(new TimeoutException(s"timed out")))
    Future.firstCompletedOf(Seq(checkDependency, delayedFuture)).onComplete {
      case Success(result: ApiStatus) =>
        if (!result.status) {
          logger.warn(s"dependency check status attempt unsuccessful ($checkName): " + result.msg)
          retryCheck(checkInterval)
        } else {
          logger.info(s"dependency check status successful ($checkName): " + result.msg)
          promise.success(result)
        }
      case Failure(_: TimeoutException) =>
        logger.warn(s"dependency check status attempt timed out ($checkName)")
        retryCheck()
      case Failure(e) =>
        logger.warn(s"dependency check status attempt unsuccessful ($checkName): " + e.getMessage)
        retryCheck(checkInterval)
    }

    promise.future
  }

}
