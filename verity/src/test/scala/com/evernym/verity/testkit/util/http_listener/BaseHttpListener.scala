package com.evernym.verity.testkit.util.http_listener

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Route
import com.evernym.verity.actor.testkit.actor.ActorSystemVanilla
import com.evernym.verity.http.common.HttpServerUtil
import com.evernym.verity.logging.LoggingUtil.getLoggerByName
import com.evernym.verity.util2.UrlParam
import com.typesafe.scalalogging.Logger

trait BaseHttpListener[T] extends HttpServerUtil {

  private var allMsgs: List[T] = List.empty

  def addToMsgs(msg: T): Unit = allMsgs = allMsgs ++ List(msg)

  def getAndResetReceivedMsgs: List[T] = {
    val lic = allMsgs
    allMsgs = List.empty
    lic
  }

  def msgCount: Int = allMsgs.size

  val logger: Logger = getLoggerByName("edge-http")

  override lazy implicit val system: ActorSystem = ActorSystemVanilla("edge-json-msg")

  protected def listeningEndpoint: UrlParam

  def listeningUrl: String

  def edgeRoute: Route

  def init(): Unit = {
    Http().newServerAt("localhost", listeningEndpoint.port).bind(corsHandler(edgeRoute))
  }
}
