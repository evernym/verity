package com.evernym.verity.app_launcher

import akka.actor.ActorSystem
import akka.pattern.after
import com.evernym.verity.config.ConfigConstants
import com.evernym.verity.config.validator.base.ConfigReadHelper
import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByClass
import com.evernym.verity.util.healthcheck.{ApiStatus, HealthChecker}

import java.util.UUID
import scala.concurrent.duration.{DurationInt, DurationLong, FiniteDuration}
import scala.concurrent.{Await, ExecutionContext, Future, Promise, TimeoutException}
import scala.language.postfixOps
import scala.util.{Failure, Success}


case class StartupProbeStatus(status: Boolean,
                              akkaStorageStatus: String,
                              walletStorageStatus: String,
                              blobStorageStatus: String,
                              ledgerPoolStatus: String,
                              vdrToolsStatus: String)

/**
 * checks to perform during agent service start to make sure that basic required
 * external dependencies are available/responding (like DynamoDB, MySql, S3, Ledger etc)
 */
object LaunchPreCheck {

  private val logger = getLoggerByClass(getClass)

  def waitForRequiredDepsIsOk(healthChecker: HealthChecker,
                              system: ActorSystem,
                              executionContext: ExecutionContext): Unit = {
    val checkId = UUID.randomUUID().toString
    logger.info(s"[$checkId] Startup probe check started")
    val startupProbeFut = startupProbe(checkId, healthChecker)(executionContext, system)
    val probeTimeout = 10.seconds
    do {
      try {
        val result = Await.result(startupProbeFut, probeTimeout)
        if (result.status) {
          logger.info(s"[$checkId] Startup probe check successful")
          return
        } else {
          logger.info(s"[$checkId] Startup probe check not successful: \n" + result.toString)
        }
      } catch {
        case e: TimeoutException =>
          logger.info(s"[$checkId] Startup probe check attempt timedout (will re-check after $probeTimeout)")
        case e: InterruptedException =>
          logger.info(s"[$checkId] Startup probe check attempt interrupted (will re-check after $probeTimeout): " + e.getMessage)
        case e: RuntimeException =>
          logger.warn(s"[$checkId] Startup probe check attempt unsuccessful (will re-check after $probeTimeout): " + e.getMessage)
      }
    } while (true)
  }

  private def startupProbe(checkId: String,
                           healthChecker: HealthChecker)
                          (implicit executionContext: ExecutionContext, system: ActorSystem): Future[StartupProbeStatus] = {
    val config = ConfigReadHelper(system.settings.config)
    val ledgerTimeoutSeconds = config.getIntOption(ConfigConstants.LIB_INDY_LEDGER_POOL_CONFIG_CONN_MANAGER_OPEN_TIMEOUT).getOrElse(60)
    val ledgerFuture = checkApiStatus(checkId, "ledger", {healthChecker.checkLedgerPoolStatus}, ledgerTimeoutSeconds.seconds)
    val akkaStorageFuture = checkApiStatus(checkId, "akka-persistence", {healthChecker.checkAkkaStorageStatus})
    val walletStorageFuture = checkApiStatus(checkId, "wallet-storage", {healthChecker.checkWalletStorageStatus})
    val blobStorageFuture = checkApiStatus(checkId, "blob-storage", {healthChecker.checkBlobStorageStatus})
    val vdrFuture = checkApiStatus(checkId, "vdrtools", {healthChecker.checkVDRToolsStatus}, ledgerTimeoutSeconds.seconds)
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

  private def checkApiStatus(checkId: String,
                             checkName: String,
                             checkDependency: => Future[ApiStatus],
                             checkRetryInterval: FiniteDuration = 10.seconds,
                             promise: Promise[ApiStatus] = Promise[ApiStatus]())
                            (implicit executionContext: ExecutionContext, system: ActorSystem): Future[ApiStatus] = {

    def retryCheck(waitInterval: FiniteDuration = 0.seconds): Future[ApiStatus] = {
      after(waitInterval, system.scheduler) {
        val nextCheckRetryInterval = {
          val retryInterval = checkRetryInterval.plus(checkRetryInterval.toMillis*2 millis)
          if (retryInterval.toSeconds > 5) 5.seconds    //retry at least after every 5 seconds
          else retryInterval
        }
        checkApiStatus(checkId, checkName, checkDependency, nextCheckRetryInterval, promise)
      }
    }

    //execute the dependency check and process result accordingly
    try {
      val delayedFuture = after(checkRetryInterval, system.scheduler)(Future.failed(new TimeoutException(s"timed out")))
      Future.firstCompletedOf(Seq(checkDependency, delayedFuture)).onComplete {
        case Success(result: ApiStatus) =>
          if (result.status) {
            logger.info(s"[$checkId] [$checkName] dependency check status successful: " + result.msg)
            promise.success(result)
          } else {
            logger.warn(s"[$checkId] [$checkName] dependency check status attempt unsuccessful: " + result.msg)
            retryCheck(checkRetryInterval)
          }
        case Failure(e: TimeoutException) =>
          logger.warn(s"[$checkId] [$checkName] dependency check status attempt failed with ${e.getClass.getSimpleName}")
          //we are not passing 'checkInterval' in below `retryCheck` function call
          // because it is already timed out, it means it has already waited enough so we can try the next attempt immediately
          retryCheck()
        case Failure(e) =>
          logger.warn(s"[$checkId] [$checkName] dependency check status attempt failed with ${e.getClass.getSimpleName}: " + e.getMessage)
          retryCheck(checkRetryInterval)
      }
    } catch {
      case e: RuntimeException =>
        logger.warn(s"[$checkId] [$checkName] dependency check status attempt failed with ${e.getClass.getSimpleName}: " + e.getMessage)
        retryCheck(checkRetryInterval)
    }

    promise.future
  }

}
