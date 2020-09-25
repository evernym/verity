package com.evernym.verity.util

import scala.reflect.ClassTag

object ExceptionUtil {

  def allow[E <: Throwable](f: => Unit)(implicit classTag: ClassTag[E]): Unit = {
    try {
      f
    }
    catch {
      case t: Throwable =>
        val clazz = classTag.runtimeClass
        if (!clazz.isAssignableFrom(t.getClass)) throw t
    }
  }

  def allowCause[E <: Throwable](f: => Unit)(implicit classTag: ClassTag[E]): Unit = {
    try {
      f
    }
    catch {
      case t: Throwable =>
        val clazz = classTag.runtimeClass
        val cause = t.getCause
        if (cause == null) throw t
        if (!clazz.isAssignableFrom(cause.getClass)) throw t
    }
  }

  def allowCauseConditionally[E <: Throwable](test: E => Boolean)(f: => Unit)(implicit classTag: ClassTag[E]): Unit = {
    try {
      f
    }
    catch {
      case t: Throwable =>
        val clazz = classTag.runtimeClass
        val cause = t.getCause
        if (cause == null) throw t
        if (clazz.isAssignableFrom(cause.getClass)) {
          val asType = cause.asInstanceOf[E]
          if (!test(asType)) throw t
        }
        else {
          throw t
        }
    }
  }

}
