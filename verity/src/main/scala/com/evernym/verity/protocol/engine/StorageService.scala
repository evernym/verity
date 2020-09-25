package com.evernym.verity.protocol.engine

import scala.util.Try

trait StorageService {

  type ItemId = String

  /**
    *
    * @param id unique item id
    * @param data
    * @param cb
    */
  def write(id: ItemId, data: Array[Byte], cb: Try[Any] => Unit): Unit

  /**
    *
    * @param id unique item id
    * @param cb
    */
  def read(id: ItemId, cb: Try[Array[Byte]] => Unit): Unit

}
