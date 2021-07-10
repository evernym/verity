package com.evernym.verity.protocol.legacy.services

import com.evernym.verity.util2.Exceptions.HandledErrorException

import scala.concurrent.Future

trait TokenToActorMappingProvider {

  def createToken(uid: String): Future[Either[HandledErrorException, String]]

}
