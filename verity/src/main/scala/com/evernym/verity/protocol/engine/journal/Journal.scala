package com.evernym.verity.protocol.engine.journal

import com.typesafe.scalalogging.Logger

/**
  * For setting global detailed logging attributes. Implementations of the
  * DetailedLogging trait at instantiation will read these attributes.
  *
  * It's understood that this shared mutable state is an antipattern.
  * Therefore, we need to find a way to accomplish the same without
  * an object; probably an Akka Extension.
  *
  */
object Journal {
  var shouldIndent = false //default is no indentation
  var overrideLogger: Option[Logger] = None

  def goIn(): Unit = if (shouldIndent) _depth += 1
  def comeOut(): Unit = if (shouldIndent) _depth -= 1

  private var _depth = 0
  def depth: Int = _depth
  def indent(str: String): String = spacing + str
  private def spacing: String = tab * _depth
  private val tab = " " * 2

}

trait Journal {

  protected def logger: Logger
  protected def journalContext: JournalContext

  protected def withLog[A](descr: String)(block: => A): A = _withLog(descr)((() => block)())
  protected def withLog[A](descr: String, details: Any)(block: => A): A = _withLog(descr, Option(details))((() => block)())
  protected def withLog[A](descr: String, details: Any, tag: Tag)(block: => A): A = _withLog(descr, Option(details), Option(tag))((() => block)())

  protected def record(descr: String): Unit = _record(msg(descr, None, None))
  protected def record(descr: String, details: Any): Unit = _record(msg(descr, Option(details), None))
  protected def record(descr: String, details: Any, tag: Tag): Unit = _record(msg(descr, Option(details), Option(tag)))

  protected def recordWarn(descr: String): Unit = _recordWarn(msg(descr, None, None))
  protected def recordWarn(descr: String, details: Any): Unit = _recordWarn(msg(descr, Option(details), None))
  protected def recordWarn(descr: String, details: Any, tag: Tag): Unit = _recordWarn(msg(descr, Option(details), Option(tag)))

  private [journal] def _withLog[A](descr: String, details: Option[Any]=None, tag: Option[Tag]=None)(block: => A): A

  private [journal] lazy val _logger: Logger = Journal.overrideLogger getOrElse logger

  private [journal] val baseRecord: String => Unit
  private [journal] val baseRecordWarn: String => Unit

  private [journal] lazy val _record: String => Unit = if (Journal.shouldIndent) baseRecord compose Journal.indent else baseRecord
  private [journal] lazy val _recordWarn: String => Unit = if (Journal.shouldIndent) baseRecordWarn compose Journal.indent else baseRecordWarn

  /** sub-classes can overload for special processing */
  def formatDescription(descr: String): String = descr

  @inline
  private def msg(descr: String, details: Option[Any], tag: Option[Tag]): String = {
    val dets = formatDetails(details, tag)
    val dets2 = if (dets.isEmpty) "" else ": " + dets
    val descr2 = formatDescription(descr)
    s"{${journalContext.tag}} $descr2$dets2"
  }

  def formatDetails(details: Option[Any], tag: Option[Tag]): String = {
    (details, tag) match {
      case (Some(d), None   ) => d.toString
      case (Some(d), Some(t)) => t(d)
      case _ => ""
    }
  }
}
