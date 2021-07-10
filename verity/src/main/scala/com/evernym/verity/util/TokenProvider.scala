package com.evernym.verity.util

import com.evernym.verity.constants.Constants.{TOKEN, VALID_TOKEN_LENGTH_RANGE}
import com.evernym.verity.util2.Exceptions.InvalidValueException


object TokenProvider {

  def getNewToken: String = {
    val token = Base64Util.getBase64String(None, VALID_TOKEN_LENGTH_RANGE.head)
    checkIfTokenLengthIsValid(token)
    token
  }

  def checkIfTokenLengthIsValid(token: String): Unit = {
    val isInvalidLength = ! VALID_TOKEN_LENGTH_RANGE.contains(token.length)
    if (isInvalidLength) throw new InvalidValueException(
      Option(s"actual length of given $TOKEN ($token) is: ${token.length}, " +
        s"expected length is: $VALID_TOKEN_LENGTH_RANGE"))
  }
}
