package com.evernym.verity.protocol.engine

import java.util.UUID

import org.slf4j.MarkerFactory

package object util {

  // TODO: All of these utils have been copied from the common.util package.
  // TODO: Do we need to have a common util package to avoid so much code duplication?

  //syntactic sugar for partial functions types
  type ?=>[-A, +B] = PartialFunction[A, B]

  def getNewActorIdFromSeed(seed: String): String = {
    //TODO TODO TODO: need to finalize about creating a new actor id based on seed
    UUID.nameUUIDFromBytes(seed.getBytes).toString
  }

  object marker {
    val protocol = MarkerFactory.getMarker("PROTOCOL")
  }

  def getErrorMsg(e: Throwable): String = {
    Option(e.getMessage).getOrElse {
      Option(e.getCause).map(_.getMessage).getOrElse(e.toString)
    }
  }
}
