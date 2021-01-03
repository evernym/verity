package com.evernym.verity.protocol.engine.journal

import com.typesafe.scalalogging.Logger
import org.slf4j.Marker

import scala.util.Random


trait JournalLogging extends Journal {

  protected def logger: Logger
  protected val journalContext: JournalContext
  def logMarker: Option[Marker] = None

  private [journal] lazy val baseRecord: String => Unit = logMarker map { m => _logger.debug(m, _: String)} getOrElse _logger.debug(_)
  private [journal] lazy val baseRecordWarn: String => Unit = logMarker map { m => _logger.warn(m, _: String)} getOrElse _logger.warn(_)

  private [journal] def _withLog[A](descr: String, details: Option[Any]=None, tag: Option[Tag]=None)(block: => A): A = {
    if (_logger.underlying.isDebugEnabled) {
      val rtag = Random.alphanumeric.take(5).mkString
      val dets = formatDetails(details, tag)
      val descr2 = formatDescription(descr)
      _record(s"{${journalContext.tag} begin $descr2: [$rtag] $dets")
      Journal.goIn()
      try {
        block // call-by-name
      } finally {
        Journal.comeOut()
        _record(s"{${journalContext.tag}} end $descr2: [$rtag]")
      }
    } else {
      block // call-by-name
    }
  }
}


