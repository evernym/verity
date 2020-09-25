package com.evernym.verity.protocol.engine.journal

trait JournalProtocolSupport {
  this: JournalLogging =>

  import Tag._

  //TODO would be nice to make this case insensitive, probably with regex and capture groups
  val tagMap: Map[String, Tag] = Map(
    "domain" -> cyan,
    "container" -> magenta,
    "control" -> blue,
    "signal" -> yellow,
    "create" -> bold,
    "protocol" -> red
  )

  def replace(base: String, mapEntry: (String, Tag)): String = {
    val (searchStr, tag) = mapEntry
    base.replace(searchStr, tag(searchStr))
  }

  override def formatDescription(descr: String): String = tagMap.foldLeft(descr)(replace)

}
