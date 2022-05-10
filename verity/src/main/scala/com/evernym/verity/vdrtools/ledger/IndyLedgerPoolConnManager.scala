package com.evernym.verity.vdrtools.ledger

import akka.actor.ActorSystem
import com.evernym.vdrtools.IndyException
import com.evernym.verity.util2.Status.StatusDetailException
import com.evernym.verity.actor.appStateManager.AppStateConstants._
import com.evernym.verity.actor.appStateManager.{AppStateUpdateAPI, ErrorEvent, RecoverIfNeeded, SeriousSystemError}
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.config.ConfigUtil.findTAAConfig
import com.evernym.verity.config.{AppConfig, ConfigConstants, ConfigUtil}
import com.evernym.verity.constants.Constants.{LEDGER_TXN_PROTOCOL_V1, LEDGER_TXN_PROTOCOL_V2}
import com.evernym.verity.ledger._
import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByClass
import com.evernym.verity.util.HashAlgorithm.SHA256
import com.evernym.verity.util.HashUtil.byteArray2RichBytes
import com.evernym.verity.util.Util._
import com.evernym.verity.util.{HashUtil, Util}
import com.evernym.verity.util2.Exceptions
import com.evernym.verity.vault.wallet_api.WalletAPI
import com.typesafe.scalalogging.Logger
import com.evernym.vdrtools.pool.Pool
import com.evernym.vdrtools.pool.PoolJSONParameters.CreatePoolLedgerConfigJSONParameter

import java.util.concurrent.TimeUnit
import scala.collection.mutable
import scala.compat.java8.FutureConverters.{toScala => toFuture}
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, ExecutionException, Future}


class IndyLedgerPoolConnManager(val actorSystem: ActorSystem,
                                appConfig: AppConfig,
                                executionContext: ExecutionContext,
                                poolConfigName: Option[String] = None,
                                genesisFile: Option[String] = None)
  extends ConfigurableLedgerPoolConnManager(appConfig) {
  implicit def futureExecutionContext: ExecutionContext = executionContext

  val openTimeout: Duration = Duration.apply(
    appConfig.getIntOption(ConfigConstants.LIB_INDY_LEDGER_POOL_CONFIG_CONN_MANAGER_OPEN_TIMEOUT).getOrElse(60),
    TimeUnit.SECONDS)

  override def connHandle: Option[Int] = poolConn.map(_.getPoolHandle)

  private var heldPoolConn: Option[Pool] = None

  private def configName = poolConfigName
    .getOrElse(appConfig.getStringReq(ConfigConstants.LIB_INDY_LEDGER_POOL_NAME))

  val genesisTxnFilePath: String = genesisFile.getOrElse(
    appConfig.getStringReq(ConfigConstants.LIB_INDY_LEDGER_POOL_TXN_FILE_LOCATION))

  val logger: Logger = getLoggerByClass(classOf[IndyLedgerPoolConnManager])

  def poolConn: Option[Pool] = heldPoolConn

  def poolConn_! : Pool = poolConn match {
    case Some(pc) =>
      AppStateUpdateAPI(actorSystem).publishEvent(RecoverIfNeeded(CONTEXT_LEDGER_OPERATION))
      pc
    case None     =>
      val ex = new RuntimeException("pool not opened")
      AppStateUpdateAPI(actorSystem).publishEvent(ErrorEvent(SeriousSystemError, CONTEXT_LEDGER_OPERATION, ex))
      throw ex
  }

  def isConnected: Boolean = poolConn.isDefined
  def isNotConnected: Boolean = poolConn.isEmpty

  private def createPoolLedgerConfig(): Unit = {
    try {
      val createPoolLedgerConfigJSONParameter = new CreatePoolLedgerConfigJSONParameter(genesisTxnFilePath)
      Pool.createPoolLedgerConfig(
        configName,
        createPoolLedgerConfigJSONParameter.toJson).get
    } catch {
      case e: Exception =>
        val errorMsg = s"error while creating ledger pool config file " +
          s"(genesisTxnFilePath: '$genesisTxnFilePath') " +
          s"(detail => ${Exceptions.getErrorMsg(e)})"
        logger.error(errorMsg)
        AppStateUpdateAPI(actorSystem).publishEvent(ErrorEvent(SeriousSystemError, CONTEXT_LEDGER_OPERATION, e, Option(errorMsg)))
    }
  }

  // This is not particularly thread safe
  def open(): Unit = {
    if (poolConn.isEmpty) {
      close()
      deletePoolLedgerConfig()
      createPoolLedgerConfig()
      val ledgerTxnProtocolVer = getLedgerTxnProtocolVersion(appConfig)
      Pool.setProtocolVersion(ledgerTxnProtocolVer).get
      // Start with an empty mutable Map, and add optional agency->vdrtools->ledger->pool-config
      val poolConfig: mutable.Map[String, Any] = mutable.Map.empty[String, Any]
      appConfig.getIntOption(ConfigConstants.LIB_INDY_LEDGER_POOL_CONFIG_TIMEOUT) match {
        case None =>
        case Some(timeout: Int) if timeout > 0 => poolConfig("timeout") = timeout
        case Some(_) => throw new RuntimeException("ledger pool config's timeout must be an integer greater than 0")
      }
      appConfig.getIntOption(ConfigConstants.LIB_INDY_LEDGER_POOL_CONFIG_EXTENDED_TIMEOUT) match {
        case None =>
        case Some(timeout: Int) if timeout > 0 => poolConfig("extended_timeout") = timeout
        case Some(_) => throw new RuntimeException("ledger pool config's extended_timeout must be an integer greater than 0")
      }
      appConfig.getIntOption(ConfigConstants.LIB_INDY_LEDGER_POOL_CONFIG_CONN_LIMIT) match {
        case None =>
        case Some(timeout: Int) if timeout > 0 => poolConfig("conn_limit") = timeout
        case Some(_) => throw new RuntimeException("ledger pool config's conn_limit must be an integer greater than 0")
      }
      appConfig.getIntOption(ConfigConstants.LIB_INDY_LEDGER_POOL_CONFIG_CONN_ACTIVE_TIMEOUT) match {
        case None =>
        case Some(timeout: Int) if timeout > 0 => poolConfig("conn_active_timeout") = timeout
        case Some(_) => throw new RuntimeException("ledger pool config's conn_active_timeout must be an integer greater than 0")
      }
      // Convert the poolConfig from a mutable Map to an immutable Map and then to a JSON string
      val poolConfigJson = DefaultMsgCodec.toJson(poolConfig.toMap)
      val openFut =
        toFuture(Pool.openPoolLedger(configName, poolConfigJson))
          .flatMap(p => enableTAA(p).recover{ case e: RuntimeException => p.close(); throw e})

      // TODO at some point we should consider making this non-blocking. But currently, we only run this on startup
      //  so blocking is not a major scaling issue.
      heldPoolConn = Some(Await.result(openFut, openTimeout))
      logger.debug("pool connection established")
    } else {
      logger.debug("pool connection is already established")
    }
  }

  def deletePoolLedgerConfig(): Unit = {
    try {
      Pool.deletePoolLedgerConfig(configName).get()
    } catch {
      //TODO: Shall we catch some specific exception?
      case e: ExecutionException if Option(e.getCause).exists(e => e.isInstanceOf[IndyException]) =>
        val cause = e.getCause.asInstanceOf[IndyException]
        logger.debug(s"error while trying to delete pool ledger config ($configName): ${cause.getSdkErrorCode}:${cause.getSdkMessage}")
      case e: IndyException =>
        logger.debug(s"error while trying to delete pool ledger config ($configName): ${e.getSdkErrorCode}:${e.getSdkMessage}")
      case e: Exception =>
        logger.debug(s"error while trying to delete pool ledger config ($configName): " + e.getMessage)
    }
  }

  def close(): Unit = {
    if (isConnected) {
      heldPoolConn.foreach(_.closePoolLedger.get())
      heldPoolConn = None
    }
  }

  def enableTAA(p: Pool): Future[Pool] = {
    if (ConfigUtil.isTAAConfigEnabled(appConfig)) {
      createTxnExecutor(None, Some(p), None, futureExecutionContext).getTAA(
        Submitter("9mDREAANbTWQqbmrdZYjQz", None) // Using a hard coded random DID. This is not ideal.
      ).map {
        _.taa
      }.recover {
        case StatusDetailException(s) => throw OpenConnException(s"Unable to retrieve TAA from ledger -- ${s.statusCode} - ${s.statusMsg}")
      }.map { ledgerTaa: LedgerTAA =>
        val expectedDigest = HashUtil.hash(SHA256)(ledgerTaa.version + ledgerTaa.text).hex
        val configuredTaa: Option[TransactionAuthorAgreement] = findTAAConfig(appConfig, ledgerTaa.version)
        configuredTaa match {
          case Some(taa) =>
            if (expectedDigest.toLowerCase() != taa.digest.toLowerCase()) {
              throw OpenConnException("Configured TAA Digest doesn't match ledger TAA")
            } else {
              configuredTaa
            }
          case None =>
            throw OpenConnException("TAA is not configured")
        }
      }.map { configuredTaa =>
        currentTAA = configuredTaa
        p
      }
    } else {
      currentTAA = None
      Future.successful(p)
    }

  }

  override def txnExecutor(walletAPI: Option[WalletAPI]): LedgerTxnExecutor = {
    createTxnExecutor(walletAPI, poolConn, currentTAA, futureExecutionContext)
  }

  private def createTxnExecutor(walletAPI: Option[WalletAPI],
                                pool: Option[Pool],
                                taa: Option[TransactionAuthorAgreement],
                                executionContext: ExecutionContext): LedgerTxnExecutor = {
    Util.getLedgerTxnProtocolVersion(appConfig) match {
      case LEDGER_TXN_PROTOCOL_V1 => new LedgerTxnExecutorV1(actorSystem, appConfig, walletAPI, pool, taa, executionContext)
      case LEDGER_TXN_PROTOCOL_V2 => new LedgerTxnExecutorV2(actorSystem, appConfig, walletAPI, pool, taa, executionContext)
      case x => throw new RuntimeException(s"ledger txn protocol version $x not yet supported")
    }
  }
}

class BasePoolConnectionException extends Exception {
  val message: String = ""

  override def getMessage: String = message
}

case class PoolConnectionNotOpened(override val message: String = "") extends BasePoolConnectionException
