package com.evernym.verity.util

import java.util.UUID

object MsgIdProvider {

  /**
   * provides new msg id (shall we instead use UUID itself?)
   * @return
   */
  def getNewMsgId: String = UUID.randomUUID().toString
}
