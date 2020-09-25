package com.evernym.verity.libindy

import java.util.concurrent.TimeUnit

import com.evernym.verity.constants.Constants.{LEDGER_TXN_PROTOCOL_V1, LEDGER_TXN_PROTOCOL_V2}
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.apphealth.AppStateConstants._
import com.evernym.verity.apphealth.{AppStateManager, ErrorEventParam, SeriousSystemError}
import com.evernym.verity.config.CommonConfig.LIB_INDY_LEDGER_TAA_AUTO_ACCEPT
import com.evernym.verity.config.ConfigUtil.{findTAAConfig, nowTimeOfAcceptance}
import com.evernym.verity.config.{AppConfig, CommonConfig, ConfigUtil}
import com.evernym.verity.ledger._
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.evernym.verity.util.HashAlgorithm.SHA256
import com.evernym.verity.util.HashUtil.byteArray2RichBytes
import com.evernym.verity.util.Util._
import com.evernym.verity.util.{HashUtil, Util}
import com.evernym.verity.vault.WalletAPI
import com.evernym.verity.Exceptions
import com.typesafe.scalalogging.Logger
import org.hyperledger.indy.sdk.pool.Pool
import org.hyperledger.indy.sdk.pool.PoolJSONParameters.CreatePoolLedgerConfigJSONParameter

import scala.collection.mutable
import scala.compat.java8.FutureConverters.{toScala => toFuture}
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}


class IndyLedgerPoolConnManager(appConfig: AppConfig, poolConfigName: Option[String] = None)
  extends ConfigurableLedgerPoolConnManager(appConfig)
    with LibIndyCommon {

  val openTimeout: Duration = Duration.apply(
    appConfig.getConfigIntOption(CommonConfig.LIB_INDY_LEDGER_POOL_CONFIG_CONN_MANAGER_OPEN_TIMEOUT).getOrElse(60),
    TimeUnit.SECONDS)

  override def connHandle: Option[Int] = poolConn.map(_.getPoolHandle)

  private var heldPoolConn: Option[Pool] = None

  private val configName = poolConfigName
    .getOrElse(appConfig.getConfigStringReq(CommonConfig.LIB_INDY_LEDGER_POOL_NAME))

  val logger: Logger = getLoggerByClass(classOf[IndyLedgerPoolConnManager])

  def poolConn: Option[Pool] = heldPoolConn

  def poolConn_! : Pool = poolConn.getOrElse(throw new RuntimeException("pool not opened"))

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
        val errorMsg = s"error while creating ledger " +
          s"pool config file (detail => ${Exceptions.getErrorMsg(e)})"
        AppStateManager << ErrorEventParam(SeriousSystemError, CONTEXT_LEDGER_OPERATION, e, Option(errorMsg))
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
      // Start with an empty mutable Map, and add optional agency->libindy->ledger->pool-config
      val poolConfig: mutable.Map[String, Any] = mutable.Map.empty[String, Any]
      appConfig.getConfigIntOption(CommonConfig.LIB_INDY_LEDGER_POOL_CONFIG_TIMEOUT) match {
        case None =>
        case Some(timeout: Int) if timeout > 0 => poolConfig("timeout") = timeout
        case Some(_) => throw new RuntimeException(s"ledger pool config's timeout must be an integer greater than 0")
      }
      appConfig.getConfigIntOption(CommonConfig.LIB_INDY_LEDGER_POOL_CONFIG_EXTENDED_TIMEOUT) match {
        case None =>
        case Some(timeout: Int) if timeout > 0 => poolConfig("extended_timeout") = timeout
        case Some(_) => throw new RuntimeException(s"ledger pool config's extended_timeout must be an integer greater than 0")
      }
      appConfig.getConfigIntOption(CommonConfig.LIB_INDY_LEDGER_POOL_CONFIG_CONN_LIMIT) match {
        case None =>
        case Some(timeout: Int) if timeout > 0 => poolConfig("conn_limit") = timeout
        case Some(_) => throw new RuntimeException(s"ledger pool config's conn_limit must be an integer greater than 0")
      }
      appConfig.getConfigIntOption(CommonConfig.LIB_INDY_LEDGER_POOL_CONFIG_CONN_ACTIVE_TIMEOUT) match {
        case None =>
        case Some(timeout: Int) if timeout > 0 => poolConfig("conn_active_timeout") = timeout
        case Some(_) => throw new RuntimeException(s"ledger pool config's conn_active_timeout must be an integer greater than 0")
      }
      // Convert the poolConfig from a mutable Map to an immutable Map and then to a JSON string
      val poolConfigJson = DefaultMsgCodec.toJson(poolConfig.toMap)

      val openFut = toFuture(
        Pool.openPoolLedger(configName, poolConfigJson)
      )
      .flatMap(enableTAA)

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
      Pool.deletePoolLedgerConfig(configName)
    } catch {
      //TODO: Shall we catch some specific exception?
      case e: Exception =>
        logger.debug("no ledger pool config file to delete")
    }
  }

  def close(): Unit = {
    if (isConnected) {
      heldPoolConn.map(_.closePoolLedger)
      heldPoolConn = None
    }
  }

  def enableTAA(p: Pool): Future[Pool] = {
    if (ConfigUtil.isTAAConfigEnabled(appConfig)) {
      createTxnExecutor(None, Some(p), None).getTAA(
        Submitter("9mDREAANbTWQqbmrdZYjQz", None) // Using a hard coded random DID. This is not ideal.
      )
        .map {
          case Right(taaResp: GetTAAResp) => taaResp.taa
          case Left(s) => throw OpenConnException(s"Unable to retrieve TAA from ledger -- ${s.statusCode} - ${s.statusMsg}")
        }
        .map { ledgerTaa: LedgerTAA =>
          val expectedDigest = HashUtil.hash(SHA256)(ledgerTaa.version + ledgerTaa.text).hex

          val autoAccept = appConfig.getConfigBooleanOption(LIB_INDY_LEDGER_TAA_AUTO_ACCEPT).getOrElse(false)
          val configuredTaa:Option[TransactionAuthorAgreement] = if(!autoAccept) {
            findTAAConfig(appConfig, ledgerTaa.version)
          }
          else {
            // This for demo, testing or otherwise when connecting to a ledger that don't have a legally binding TAA
            Some(TransactionAuthorAgreement(
              ledgerTaa.version,
              expectedDigest,
              "on_file",
              nowTimeOfAcceptance()
            ))
          }

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
        }
        .map { configuredTaa =>
          currentTAA = configuredTaa
          p
        }
    }
    else {
      currentTAA = None
      Future.successful(p)
    }

  }

  override def txnExecutor(walletAPI: Option[WalletAPI]): LedgerTxnExecutor = {
    createTxnExecutor(walletAPI, poolConn, currentTAA)
  }

  private def createTxnExecutor(walletAPI: Option[WalletAPI],
                                pool: Option[Pool],
                                taa: Option[TransactionAuthorAgreement]): LedgerTxnExecutor = {
    Util.getLedgerTxnProtocolVersion(appConfig) match {
      case LEDGER_TXN_PROTOCOL_V1 => new LedgerTxnExecutorV1(appConfig, walletAPI, pool, taa)
      case LEDGER_TXN_PROTOCOL_V2 => new LedgerTxnExecutorV2(appConfig, walletAPI, pool, taa)
      case x => throw new RuntimeException(s"ledger txn protocol version $x not yet supported")
    }
  }
}

class BasePoolConnectionException extends Exception {
  val message: String = ""

  override def getMessage: String = message
}

case class PoolConnectionNotOpened(override val message: String = "") extends BasePoolConnectionException
