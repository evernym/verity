package com.evernym.verity.http.common

import java.io.InputStream
import java.security.{KeyStore, SecureRandom}

import akka.actor.ActorSystem
import akka.http.scaladsl.{ConnectionContext, HttpsConnectionContext}
import com.evernym.verity.actor.appStateManager.AppStateConstants._
import com.evernym.verity.config.CommonConfig.{KEYSTORE_LOCATION, KEYSTORE_PASSWORD}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.Exceptions
import com.evernym.verity.actor.appStateManager.AppStateUpdateAPI._
import com.evernym.verity.actor.appStateManager.{ErrorEvent, SeriousSystemError}
import com.typesafe.scalalogging.Logger
import javax.net.ssl.{KeyManagerFactory, SSLContext, TrustManagerFactory}


trait HttpsSupport {

  implicit def system: ActorSystem
  def appConfig: AppConfig
  def logger: Logger

  def getHttpsConnectionContext: Option[HttpsConnectionContext] = {
    try {
      val ks: KeyStore = KeyStore.getInstance("JKS")
      val location = appConfig.getConfigStringReq(KEYSTORE_LOCATION)
      val keystore: InputStream = getClass.getClassLoader.getResourceAsStream(location)
      val password: Array[Char] = appConfig.getConfigStringReq(KEYSTORE_PASSWORD).toCharArray
      require(keystore != null, "Keystore required to run HTTPS binding!")
      ks.load(keystore, password)

      val keyManagerFactory: KeyManagerFactory = KeyManagerFactory.getInstance("SunX509")
      keyManagerFactory.init(ks, password)

      val tmf: TrustManagerFactory = TrustManagerFactory.getInstance("SunX509")
      tmf.init(ks)

      val sslContext: SSLContext = SSLContext.getInstance("TLS")
      sslContext.init(keyManagerFactory.getKeyManagers, tmf.getTrustManagers, new SecureRandom)
      Option(ConnectionContext.httpsServer(sslContext))
    } catch {
      case e: Exception =>
        val errMsg = s"error creating https connection context: ${Exceptions.getErrorMsg(e)}"
        publishEvent(ErrorEvent(SeriousSystemError, CONTEXT_AGENT_SERVICE_INIT, e, Option(errMsg)))
        None
    }
  }
}