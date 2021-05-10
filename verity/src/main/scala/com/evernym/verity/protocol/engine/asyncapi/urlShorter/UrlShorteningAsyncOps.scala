package com.evernym.verity.protocol.engine.asyncapi.urlShorter

trait UrlShorteningAsyncOps {
  def runShorten(longUrl: String): Unit
}
