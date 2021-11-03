package com.evernym.verity.observability.logs

import com.typesafe.scalalogging.Logger

trait HasLogger {

  def logger: Logger

}
