package com.evernym.verity.event_bus.adapters


package object basic {
  case class HttpServerParam(host: String, port: Int) {
    def url: String = s"http://$host:$port"
  }
}