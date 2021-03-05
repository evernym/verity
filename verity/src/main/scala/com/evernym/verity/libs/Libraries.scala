package com.evernym.verity.libs

import com.evernym.verity.Exceptions
import com.evernym.verity.actor.appStateManager.AppStateConstants.CONTEXT_LIB_INDY_INIT
import com.evernym.verity.actor.appStateManager.AppStateUpdateAPI._
import com.evernym.verity.actor.appStateManager.{ErrorEvent, SeriousSystemError}
import com.evernym.verity.config.{AppConfig, CommonConfig}
import com.evernym.verity.libs.JnaPath._
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.typesafe.scalalogging.Logger
import org.hyperledger.indy.sdk.LibIndy

object Libraries {

  def initialize(appConfig: AppConfig): Unit = {

    val liLogger: Logger = getLoggerByClass(getClass)

    val libIndyDirPath: String = {
      val lifp = appConfig.getConfigStringReq(CommonConfig.LIB_INDY_LIBRARY_DIR_LOCATION)
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
  }
}
