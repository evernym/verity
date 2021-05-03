package com.evernym.verity.logging

import com.evernym.verity.actor.agent.AgentIdentity
import com.evernym.verity.protocol.engine.DomainIdFieldName
import org.slf4j.{Logger, Marker}

import scala.util.Try

class AgentIdentityLoggerWrapper(val identityProvider: AgentIdentity, wrapped: Logger) extends Logger{
  def idTuple(): (String, String) = (DomainIdFieldName, Try(identityProvider.domainId).getOrElse("unknown"))

  def conditionSeq(s: Seq[Any]): Array[AnyRef] =
    s.filter(_.isInstanceOf[AnyRef]).map(_.asInstanceOf[AnyRef]).toArray :+ idTuple()

  override def getName: String = wrapped.getName

  override def isTraceEnabled: Boolean = wrapped.isTraceEnabled

  override def trace(s: String): Unit = wrapped.trace(s, idTuple())

  override def trace(s: String, o: AnyRef): Unit = o match {
    case l: Seq[_] => wrapped.trace(s, conditionSeq(l): _*)
    case _ => wrapped.trace(s, Array(o, idTuple()): _*)
  }

  override def trace(s: String, o: AnyRef, o1: AnyRef): Unit = wrapped.trace(s, Array(o, o1, idTuple()): _*)

  override def trace(s: String, throwable: Throwable): Unit = wrapped.trace(s, throwable)

  override def isTraceEnabled(marker: Marker): Boolean = wrapped.isTraceEnabled(marker)

  override def trace(marker: Marker, s: String): Unit = wrapped.trace(marker, s, idTuple())

  override def trace(marker: Marker, s: String, o: AnyRef): Unit = o match {
    case l: Seq[_] => wrapped.trace(marker, s, conditionSeq(l): _*)
    case _ => wrapped.trace(marker, s, Array(o, idTuple()): _*)
  }

  override def trace(marker: Marker, s: String, o: AnyRef, o1: AnyRef): Unit = wrapped.trace(s, Array(o, o1, idTuple()): _*)

  override def trace(marker: Marker, s: String, throwable: Throwable): Unit = wrapped.trace(marker, s, throwable)

  override def trace(s: String, objects: AnyRef*): Unit = wrapped.trace(s, objects :+ idTuple(): _*)

  override def trace(marker: Marker, s: String, objects: AnyRef*): Unit = wrapped.trace(marker, s, objects :+ idTuple(): _*)

  //**

  override def isDebugEnabled: Boolean = wrapped.isDebugEnabled

  override def debug(s: String): Unit = wrapped.debug(s, idTuple())

  override def debug(s: String, o: AnyRef): Unit = o match {
    case l: Seq[_] => wrapped.debug(s, conditionSeq(l): _*)
    case _ => wrapped.debug(s, Array(o, idTuple()): _*)
  }

  override def debug(s: String, o: AnyRef, o1: AnyRef): Unit = wrapped.debug(s, Array(o, o1, idTuple()): _*)

  override def debug(s: String, throwable: Throwable): Unit = wrapped.debug(s, throwable)

  override def isDebugEnabled(marker: Marker): Boolean = wrapped.isDebugEnabled(marker)

  override def debug(marker: Marker, s: String): Unit = wrapped.debug(marker, s, idTuple())

  override def debug(marker: Marker, s: String, o: AnyRef): Unit = o match {
    case l: Seq[_] => wrapped.debug(marker, s, conditionSeq(l): _*)
    case _ => wrapped.debug(marker, s, Array(o, idTuple()): _*)
  }

  override def debug(marker: Marker, s: String, o: AnyRef, o1: AnyRef): Unit = wrapped.debug(s, Array(o, o1, idTuple()): _*)

  override def debug(marker: Marker, s: String, throwable: Throwable): Unit = wrapped.debug(marker, s, throwable)

  override def debug(s: String, objects: AnyRef*): Unit = wrapped.debug(s, objects :+ idTuple(): _*)

  override def debug(marker: Marker, s: String, objects: AnyRef*): Unit = wrapped.debug(marker, s, objects :+ idTuple(): _*)

  //**

  override def isInfoEnabled: Boolean = wrapped.isInfoEnabled

  override def info(s: String): Unit = wrapped.info(s, idTuple())

  override def info(s: String, o: AnyRef): Unit = o match {
    case l: Seq[_] => wrapped.info(s, conditionSeq(l): _*)
    case _ => wrapped.info(s, Array(o, idTuple()): _*)
  }

  override def info(s: String, o: AnyRef, o1: AnyRef): Unit = wrapped.info(s, Array(o, o1, idTuple()): _*)

  override def info(s: String, throwable: Throwable): Unit = wrapped.info(s, throwable)

  override def isInfoEnabled(marker: Marker): Boolean = wrapped.isInfoEnabled(marker)

  override def info(marker: Marker, s: String): Unit = wrapped.info(marker, s, idTuple())

  override def info(marker: Marker, s: String, o: AnyRef): Unit = o match {
    case l: Seq[_] => wrapped.info(marker, s, conditionSeq(l): _*)
    case _ => wrapped.info(marker, s, Array(o, idTuple()): _*)
  }

  override def info(marker: Marker, s: String, o: AnyRef, o1: AnyRef): Unit = wrapped.info(s, Array(o, o1, idTuple()): _*)

  override def info(marker: Marker, s: String, throwable: Throwable): Unit = wrapped.info(marker, s, throwable)

  override def info(s: String, objects: AnyRef*): Unit = wrapped.info(s, objects :+ idTuple(): _*)

  override def info(marker: Marker, s: String, objects: AnyRef*): Unit = wrapped.info(marker, s, objects :+ idTuple(): _*)

  //**

  override def isWarnEnabled: Boolean = wrapped.isWarnEnabled

  override def warn(s: String): Unit = wrapped.warn(s, idTuple())

  override def warn(s: String, o: AnyRef): Unit = o match {
    case l: Seq[_] => wrapped.warn(s, conditionSeq(l): _*)
    case _ => wrapped.warn(s, Array(o, idTuple()): _*)
  }

  override def warn(s: String, o: AnyRef, o1: AnyRef): Unit = wrapped.warn(s, Array(o, o1, idTuple()): _*)

  override def warn(s: String, throwable: Throwable): Unit = wrapped.warn(s, throwable)

  override def isWarnEnabled(marker: Marker): Boolean = wrapped.isWarnEnabled(marker)

  override def warn(marker: Marker, s: String): Unit = wrapped.warn(marker, s, idTuple())

  override def warn(marker: Marker, s: String, o: AnyRef): Unit = o match {
    case l: Seq[_] => wrapped.warn(marker, s, conditionSeq(l): _*)
    case _ => wrapped.warn(marker, s, Array(o, idTuple()): _*)
  }

  override def warn(marker: Marker, s: String, o: AnyRef, o1: AnyRef): Unit = wrapped.warn(s, Array(o, o1, idTuple()): _*)

  override def warn(marker: Marker, s: String, throwable: Throwable): Unit = wrapped.warn(marker, s, throwable)

  override def warn(s: String, objects: AnyRef*): Unit = wrapped.warn(s, objects :+ idTuple(): _*)

  override def warn(marker: Marker, s: String, objects: AnyRef*): Unit = wrapped.warn(marker, s, objects :+ idTuple(): _*)

  //**

  override def isErrorEnabled: Boolean = wrapped.isErrorEnabled

  override def error(s: String): Unit = wrapped.error(s, idTuple())

  override def error(s: String, o: AnyRef): Unit = o match {
    case l: Seq[_] => wrapped.error(s, conditionSeq(l): _*)
    case _ => wrapped.error(s, Array(o, idTuple()): _*)
  }

  override def error(s: String, o: AnyRef, o1: AnyRef): Unit = wrapped.error(s, Array(o, o1, idTuple()): _*)

  override def error(s: String, throwable: Throwable): Unit = wrapped.error(s, throwable)

  override def isErrorEnabled(marker: Marker): Boolean = wrapped.isErrorEnabled(marker)

  override def error(marker: Marker, s: String): Unit = wrapped.error(marker, s, idTuple())

  override def error(marker: Marker, s: String, o: AnyRef): Unit = o match {
    case l: Seq[_] => wrapped.error(marker, s, conditionSeq(l): _*)
    case _ => wrapped.error(marker, s, Array(o, idTuple()): _*)
  }

  override def error(marker: Marker, s: String, o: AnyRef, o1: AnyRef): Unit = wrapped.error(s, Array(o, o1, idTuple()): _*)

  override def error(marker: Marker, s: String, throwable: Throwable): Unit = wrapped.error(marker, s, throwable)

  override def error(s: String, objects: AnyRef*): Unit = wrapped.error(s, objects :+ idTuple(): _*)

  override def error(marker: Marker, s: String, objects: AnyRef*): Unit = wrapped.error(marker, s, objects :+ idTuple(): _*)
}

object AgentIdentityLoggerWrapper {
  def apply(identityProvider: AgentIdentity, wrapped: Logger): AgentIdentityLoggerWrapper =
    new AgentIdentityLoggerWrapper(identityProvider, wrapped)
}
