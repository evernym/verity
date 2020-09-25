package com.evernym.verity.protocol.protocols

import com.evernym.verity.config.CommonConfig.MESSAGES
import com.evernym.verity.config.AppConfig
import com.evernym.verity.util.StrUtil.camelToKebab

trait HasAppConfig {

  def appConfig: AppConfig

  def expiryTimeInSecondConfigNameForMsgType(typ: String): String = {
    val dashedNotationMsgType = camelToKebab(typ)
    s"$MESSAGES.$dashedNotationMsgType-expiration-time-in-seconds"
  }
}
