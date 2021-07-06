package com.evernym.verity.storage_services

object BucketLifeCycleUtil {
  
  def lifeCycleAddress(lifecycle: Option[String], id: String): String =
    lifecycle.map(x => s"$x/").getOrElse("")  + id
}
