package com.evernym.verity

import java.net.{MalformedURLException, URL}

import com.evernym.verity.Exceptions.InvalidComMethodException

object UrlParam {
  val HTTP_PROTOCOL     = "http"
  val HTTPS_PROTOCOL    = "https"

  private def protocolToBeUsed(port: Int): String = port match {
    case 443  => HTTPS_PROTOCOL
    case _    => HTTP_PROTOCOL
  }

  private def portToBeUsed(protocol: String, givenPort: Int): Int = (protocol, givenPort) match {
    case (HTTP_PROTOCOL, -1)                  => 80
    case (HTTPS_PROTOCOL, -1)                 => 443
    case (HTTP_PROTOCOL| HTTPS_PROTOCOL, _)   => givenPort
    case (protocol, _)                        => throw new RuntimeException("unsupported protocol: " + protocol)
  }

  private def buildOption(str: String): Option[String] =
    if (str == null || str.isEmpty) None
    else Option(str)

  def apply(host: String, port: Int, path: Option[String]): UrlParam = {
    UrlParam(protocolToBeUsed(port), host, port, path, None)
  }

  def apply(urlStr : String): UrlParam = {
    try {
      val url = new URL(urlStr)
      val port = portToBeUsed(url.getProtocol, url.getPort)
      val path = buildOption(url.getPath).map(_.replaceFirst("/", ""))
      val query = buildOption(url.getQuery)
      UrlParam(url.getProtocol, url.getHost, port, path, query)
    } catch {
      case e: MalformedURLException
        if
            //only retry with 'http' if no protocol is given but it does include some port
            (e.getMessage.startsWith("no protocol: ") && urlStr.contains(":")) ||
              (e.getMessage.startsWith("unknown protocol: ") && !urlStr.contains("://")) =>
          apply(HTTP_PROTOCOL + "://" + urlStr)
      case x @ (_: MalformedURLException | _: RuntimeException) =>
        throw new InvalidComMethodException(Option(s"invalid http endpoint: '$urlStr' reason: ${x.getMessage}"))
    }
  }
}

case class UrlParam(protocol: String, host: String, port: Int, private val pathOpt: Option[String], query: Option[String]=None) {
  import UrlParam._
  def isHttp: Boolean = protocol == HTTP_PROTOCOL
  def isHttps: Boolean = protocol == HTTPS_PROTOCOL
  def isLocalhost: Boolean = host == "localhost"

  private def hostAndPort: String = host + ":" + port
  def api: String = hostAndPort + pathOpt.filterNot(_.isEmpty).map("/" + _).getOrElse("")
  def url: String = s"$protocol://" + api
  def path : String = pathOpt.filterNot(_.isEmpty).getOrElse("")

  override def toString: String = api
}
