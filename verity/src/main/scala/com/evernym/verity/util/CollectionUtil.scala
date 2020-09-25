package com.evernym.verity.util

object CollectionUtil {

  /**
    * Not efficient since it does a complete linear scan
    *
    * @param coll
    * @tparam T
    */
  def containsDuplicates[T <: Seq[_]](coll: T): Boolean = {
    coll.size != coll.distinct.size
  }

}
