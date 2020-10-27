package com.evernym.verity.util

import java.util.{Map => JavaMap}

import scala.collection.GenTraversableOnce
import scala.reflect.ClassTag

import scala.language.implicitConversions

object OptionUtil {
  // JAVA Collections
  def emptyOption[T1, T2](arg: JavaMap[T1, T2]) = {
    Option(arg).filterNot(_.isEmpty)
  }

  def emptyOption[T](arg:T, isEmpty: Boolean): Option[T] = {
    Option(arg).filterNot(_ => isEmpty)
  }

  def emptyOption[T <: GenTraversableOnce[_]](arg:T): Option[T] = {
    Option(arg).filterNot(_.isEmpty)
  }

  def emptyOption(arg:String): Option[String] = {
    Option(arg).filterNot(_.isEmpty)
  }

  def emptyOption(arg:Array[_]): Option[Array[_]] = {
    Option(arg).filterNot(_.isEmpty)
  }

  def blankOption(arg: String): Option[String] = {
    Option(arg)
      .filterNot(_.trim.isEmpty)
  }

  def blankFlattenOption(arg: Option[String]): Option[String] = {
    Option(arg)
      .flatten
      .filterNot(_ == null)
      .filterNot(_.trim.isEmpty)
  }

  def blankFieldOption[K](key: K, map: Map[K, String]): Option[String] = {
    map.get(key).flatMap(blankOption)
  }

  /**
    * Converts a tuple[T] to Option[T] checking that  all
    * elements of the tuple are not null. If any element is
    * null, None is returned
    *
    * @param arg
    * @tparam T
    * @return
    */
  def fullTupleToOption[T <: Product](arg: T): Option[T] = {
    Option(arg)
      .flatMap(x => {
        if (!x.productIterator.contains(null)
              && !x.productIterator.contains(None)) {
          Some(x)
        } else None
      })
  }

  /**
    * Converts an Any to Option[T]. If the Any is of type T (or assignable to type T) then it is converted to Some[T],
    * if it is not then None is returned.
    *
    * @param test
    * @tparam T
    * @return
    */
  def orNone[T: ClassTag](test: Any): Option[T] = {
    Option(test)
      .flatMap { t =>
        val desiredClass = implicitly[ClassTag[T]].runtimeClass
        if (desiredClass.isAssignableFrom(t.getClass)) {
          Some(t.asInstanceOf[T])
        }
        else {
          None
        }
      }
  }

  def allOrNone[T](options: Seq[Option[T]]): Option[Seq[T]] = {
    if(options.forall(_.isDefined)) {
      Some(options.map(_.get))
    }
    else {
      None
    }
  }

  def allOrNone[T1, T2, T3](options: (Option[T1], Option[T2], Option[T3])): Option[(T1, T2, T3)] = {
      options match {
        case (Some(a), Some(b), Some(c)) => Some(a, b, c)
        case _ => None
      }
  }

  def falseToNone(value: Boolean): Option[Boolean] = {
    Option(value).flatMap{ x =>
      if(x) Some(x)
      else None
    }
  }

  def optionToBoolean(bool: Option[Boolean]): Boolean = {
    Option(bool).flatten.getOrElse(false)
  }

  implicit def optionToEmptyStr(v: Option[String]): String = ""
}
