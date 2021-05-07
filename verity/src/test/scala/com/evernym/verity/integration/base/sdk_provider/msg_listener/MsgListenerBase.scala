package com.evernym.verity.integration.base.sdk_provider.msg_listener

import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Route
import com.evernym.verity.http.common.HttpServerUtil
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.typesafe.scalalogging.Logger

import java.util.concurrent.LinkedBlockingDeque
import scala.concurrent.duration.Duration


trait MsgListenerBase[T] extends HttpServerUtil {

  def expectMsg(max: Duration): T = {
    val m = Option {
      if (max == Duration.Zero) {
        queue.pollFirst
      } else if (max.isFinite) {
        queue.pollFirst(max.length, max.unit)
      } else {
        queue.takeFirst
      }
    }
    m.getOrElse(throw new Exception(s"timeout ($max) during expectMsg while waiting for message"))
  }

  def port: Int
  def edgeRoute: Route
  def endpoint = s"http://localhost:$port/$baseEndpointPath"

  override def logger: Logger = getLoggerByClass(this.getClass)

  protected val baseEndpointPath: String = "edge"
  protected val queue: LinkedBlockingDeque[T] = new LinkedBlockingDeque[T]()

  protected def receiveMsg(msg: T): Unit = queue.add(msg)

  def startHttpServer(): Unit = {
    Http().newServerAt("localhost", port).bind(corsHandler(edgeRoute))
  }
}