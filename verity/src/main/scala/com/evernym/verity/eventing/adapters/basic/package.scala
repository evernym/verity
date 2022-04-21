package com.evernym.verity.eventing.adapters


package object basic {
  case class HttpServerParam(host: String, port: Int) {
    def url: String = s"http://$host:$port"
  }
}