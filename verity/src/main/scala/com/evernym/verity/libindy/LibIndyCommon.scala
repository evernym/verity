package com.evernym.verity.libindy

import com.evernym.verity.Exceptions
import com.evernym.verity.apphealth.AppStateConstants._
import com.evernym.verity.apphealth.{AppStateManager, ErrorEventParam, SeriousSystemError}
import com.evernym.verity.config.{AppConfig, CommonConfig}
import com.evernym.verity.constants.Constants._
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.evernym.verity.libindy.JnaPath.augmentJnaPath
import com.typesafe.scalalogging.Logger
import org.hyperledger.indy.sdk.LibIndy

trait LibIndyCommon {

  def appConfig: AppConfig

  val liLogger: Logger = getLoggerByClass(classOf[LibIndyCommon])

  val libIndyDirPath: String = {
    val lifp = appConfig.getConfigStringReq(CommonConfig.LIB_INDY_LIBRARY_DIR_LOCATION)
    liLogger.debug("lib indy dir path: " + lifp)
    lifp
  }
  val genesisTxnFilePath: String = {
    val gptf = appConfig.getConfigStringReq(CommonConfig.LIB_INDY_LEDGER_POOL_TXN_FILE_LOCATION)
    gptf
  }

  if(LibIndy.api == null)
  {
    augmentJnaPath()

    try {
      LibIndy.init(libIndyDirPath)
    } catch {
      case e: Exception =>
        val errorMsg = s"unable to initialize lib-indy library: ${Exceptions.getErrorMsg(e)}"
        AppStateManager << ErrorEventParam(SeriousSystemError, CONTEXT_LIB_INDY_INIT, e, Option(errorMsg))
    }
  }

  if (appConfig.getConfigStringReq(CommonConfig.LIB_INDY_WALLET_TYPE) == WALLET_TYPE_MYSQL) {
    augmentJnaPath()

    if(MySqlStorageLib.api == null) {
      MySqlStorageLib.init(libIndyDirPath)
    }
  }
}
