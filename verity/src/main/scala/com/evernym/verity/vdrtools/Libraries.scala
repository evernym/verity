package com.evernym.verity.vdrtools

import com.evernym.verity.actor.appStateManager.AppStateConstants.{CONTEXT_LIB_INDY_INIT, CONTEXT_LIB_MYSQLSTORAGE_INIT}
import com.evernym.verity.actor.appStateManager.AppStateUpdateAPI.handleError
import com.evernym.verity.actor.appStateManager.{ErrorEvent, SeriousSystemError}
import com.evernym.verity.config.{AppConfig, ConfigConstants}
import com.evernym.verity.constants.Constants.{LIBINDY_LEGACY_FLAVOR, WALLET_TYPE_MYSQL}
import com.evernym.verity.vdrtools.wallet.MySqlStorageLib
import JnaPath._
import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByClass
import com.evernym.verity.util2.Exceptions
import com.typesafe.scalalogging.Logger
import com.evernym.vdrtools.LibIndy

object Libraries {

  def initialize(appConfig: AppConfig): Unit = {

    val liLogger: Logger = getLoggerByClass(getClass)

    val libIndyDirPath: String = {
      val lifp = appConfig.getStringReq(ConfigConstants.LIB_INDY_LIBRARY_DIR_LOCATION)
      liLogger.debug("lib indy dir path: " + lifp)
      lifp
    }

    if (LibIndy.api == null) {
      try {
        augmentJnaPath()
        LibIndy.init(libIndyDirPath)
      } catch {
        case e: Exception =>
          val errorMsg = s"unable to initialize lib-indy library: ${Exceptions.getErrorMsg(e)}"
          handleError(ErrorEvent(SeriousSystemError, CONTEXT_LIB_INDY_INIT, e, Option(errorMsg)))
      }
    }

    if (appConfig.getStringReq(ConfigConstants.LIB_INDY_WALLET_TYPE) == WALLET_TYPE_MYSQL
    && appConfig.getStringReq(ConfigConstants.LIB_INDY_FLAVOR) == LIBINDY_LEGACY_FLAVOR) {
      try {
        if (MySqlStorageLib.api == null) {
          augmentJnaPath()
          MySqlStorageLib.init(libIndyDirPath)
        }
      } catch {
        case e: Exception =>
          val errorMsg = s"unable to initialize lib-mysqlstorage library: ${Exceptions.getErrorMsg(e)}"
          handleError(ErrorEvent(SeriousSystemError, CONTEXT_LIB_MYSQLSTORAGE_INIT, e, Option(errorMsg)))
      }
    }

  }
}
