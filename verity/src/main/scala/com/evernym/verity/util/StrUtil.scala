package com.evernym.verity.util

import org.apache.commons.codec.binary.Hex

import scala.reflect.ClassTag

object StrUtil {
  def hex(bytes: Array[Byte]): String = Hex.encodeHexString(bytes)

  def camelToCapitalize(name: String): String = {
    "[A-Z\\d]".r.replaceAllIn(name, {m =>
      " " + m.group(0).toUpperCase
    }).capitalize
  }

  def camelToKebab(v: String): String = {
    val t = Option(v)
      .getOrElse("")
      .filterNot(Character.isWhitespace)
      .map({c => if(c.isUpper) "-"+c.toLower else ""+c})
      .mkString
    if(t.startsWith("-")) t.substring(1) else t
  }

  protected def cleanClassName(name: String): String = {
    name.indexOf("$") match {
      case -1 => name
      case i => name.substring(0,i)
    }
  }

  def classToKebab[T: ClassTag]: String = classToKebab(implicitly[ClassTag[T]].runtimeClass)
  def classToKebab(c: Class[_]): String = camelToKebab(cleanClassName(c.getSimpleName))
}
