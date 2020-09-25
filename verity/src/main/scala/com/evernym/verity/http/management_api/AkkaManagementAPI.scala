package com.evernym.verity.http.management_api

import akka.actor.ActorSystem
import akka.http.scaladsl.server.directives.Credentials
import akka.management.scaladsl.AkkaManagement
import com.evernym.verity.constants.Constants.YES
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.CommonConfig.{AKKA_MNGMNT_HTTP_API_CREDS, AKKA_MNGMNT_HTTP_ENABLED}

import scala.concurrent.Future

trait AkkaManagementAPI {
  def appConfig: AppConfig
  def system: ActorSystem
  import scala.collection.JavaConverters._

  case class Credential(userName: String, password: String)

  lazy val allowedCredentials: List[Credential] =
    appConfig.getLoadedConfig.getObjectList(AKKA_MNGMNT_HTTP_API_CREDS).asScala.map { acs =>
      val un = acs.toConfig.getString("username")
      val pwd = acs.toConfig.getString("password")
      Credential(un, pwd)
    }.toList

  def verifyAkkaManagementAPICred(suppliedCreds: Credentials): Future[Option[String]] = {
    suppliedCreds match {
      case p @ Credentials.Provided(id) =>
        Future {
          if (allowedCredentials.exists { ac => id == ac.userName && p.verify(ac.password) }) Some(id)
          else None
        }
      case _ => Future.successful(None)
    }
  }

  def startAkkaManagementHttpApiListenerIfEnabled(): Unit = {
    val isEnabled = appConfig.getConfigStringOption(AKKA_MNGMNT_HTTP_ENABLED)
    val startAkkaManagementAPIServer = isEnabled.isDefined && isEnabled.map(_.toUpperCase).contains(YES)
    if (startAkkaManagementAPIServer) {
      lazy val management = AkkaManagement(system)
      management.start(_.withAuth(verifyAkkaManagementAPICred))
    }
  }
}
