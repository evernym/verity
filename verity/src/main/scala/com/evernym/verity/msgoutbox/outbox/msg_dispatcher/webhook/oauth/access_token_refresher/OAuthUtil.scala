package com.evernym.verity.msgoutbox.outbox.msg_dispatcher.webhook.oauth.access_token_refresher

import com.evernym.verity.msgoutbox.outbox.msg_dispatcher.webhook.oauth.access_token_refresher.OAuthAccessTokenRefresher.{AUTH_TYPE_OAUTH2, OAUTH2_VERSION_1, OAUTH2_VERSION_2}
import com.evernym.verity.util2.Exceptions.BadRequestErrorException
import com.evernym.verity.util2.Status.INVALID_VALUE

object OAuthUtil {

  def validate(authType: String, version: String, authDataParams: Map[String, String]): Unit = {
    if (authType != AUTH_TYPE_OAUTH2) {
      throw new BadRequestErrorException(INVALID_VALUE.statusCode,
        Option("authentication type not supported: " + authType))
    }
    if (! OAuthAccessTokenRefresher.SUPPORTED_VERSIONS.contains(version)) {
      throw new BadRequestErrorException(INVALID_VALUE.statusCode,
        Option("authentication version not supported: " + version))
    }
    val requiredParams = version match {
      case OAUTH2_VERSION_1 => Seq("url", "grant_type", "client_id", "client_secret")
      case OAUTH2_VERSION_2 => Seq("token")
    }
    validateAuthData(requiredParams, authDataParams)
  }

  def validateAuthData(reqAuthDataParams: Seq[String], givenAuthDataParams: Map[String, String]): Unit = {
    val notFound = reqAuthDataParams.filter(f => ! givenAuthDataParams.contains(f))

    if (notFound.nonEmpty) {
      throw new BadRequestErrorException(INVALID_VALUE.statusCode,
        Option("required fields missing: " + notFound.mkString(", ")))
    }

    val invalid = reqAuthDataParams.filter{ f =>
      val value = givenAuthDataParams.get(f)
      value.isEmpty || value.exists(v => v.trim == "" || Option(v).isEmpty)
    }
    if (invalid.nonEmpty) {
      throw new BadRequestErrorException(INVALID_VALUE.statusCode,
        Option("required fields with invalid value: " + invalid.mkString(", ")))
    }
  }
}
