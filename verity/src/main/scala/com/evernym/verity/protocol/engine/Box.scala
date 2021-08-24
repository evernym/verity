package com.evernym.verity.protocol.engine

import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByName
import com.evernym.verity.protocol.engine.journal.{JournalContext, JournalLogging, JournalProtocolSupport}
import com.typesafe.scalalogging.Logger

import scala.annotation.tailrec
import scala.util.{Failure, Success, Try}

/** This change is TEMPORARY and has to be done because older protocols
  * rely on the result of a message as the response for synchronous calls like HTTP.
  * Once we fix or deprecate those older protocols, we can remove
  * this callback change and revert back to what it was earlier.
  *
  * @param item item to be processed
  * @param callback optional and TEMPORARY, if supplied, will be executed once the item is processed successfully
  * @tparam A item type
  */
case class BoxItem[A](item: A, callback: Option[Try[Any] => Unit])

/**
  * @tparam A type of item in the queue
  * @tparam B type of result of processing one item
  */
trait BoxLike[A,B] extends JournalLogging with JournalProtocolSupport {

  def name: String
  def itemType: String

  protected lazy val logger: Logger = getLoggerByName(name)

  protected val journalContext: JournalContext = JournalContext(name)

  var queue: Seq[BoxItem[A]] = Vector.empty

  /** Changes done here is for older protocols which expects synchronous response.
    * Once a message is processed (which may happen asynchronously if there are more than one message in the queue)
    * we wanted to send it's result back to the original caller (for backward compatibility).
    * Hence added the optional callback which will be executed once the message is processed
    *
    * @param item
    * @param callback
    */
  def add(item: A, callback: Option[Try[Any] => Unit]=None): Unit = {
    record(s"queueing $itemType", item)
    queue = queue :+ BoxItem(item, callback)
  }

  def clear(): Unit = queue = Vector.empty

  def nonEmpty: Boolean = queue.nonEmpty

  /**
    * Processes all items in box
    * @return number of items processed
    */
  def process(): List[B] = {

    /**
      * runs item present in the queue
      * callback (if supplied) is executed with result (Success/Failure) of item processing
      * @param resList
      * @return
      */
    @tailrec
    def run(resList: List[B] = List.empty): List[B] = {
      if (queue.isEmpty) {
        resList
      } else {
        val hdPair = queue.head
        //it is possible that this method is re-entered during processOne, se we remove it from the queue before it's processed
        queue = queue.drop(1)
        val resp = processMsg(hdPair)
        run(resList ++ resp)
      }
    }
    run()
  }

  private def processMsg(boxItem: BoxItem[A]): Option[B] = {
    val msgResponse = Try(processOne(boxItem.item))
    (msgResponse, boxItem.callback) match {
      case (Success(r), Some(cb))  => cb(msgResponse); Option(r)
      case (Failure(_), Some(cb))  => cb(msgResponse); None
      case (Success(r), None)      => Option(r)
      case (Failure(e), None)      => throw e
    }
  }

  protected def processOneItem(msg: A): B

  final def processOne(msg: A): B = {
    record(s"processing $itemType", msg)
    processOneItem(msg)
  }

  final def processNext(): Unit  = {
    val msg = queue.head
    queue = queue.drop(1)

    processMsg(msg)
  }
}

class Box[A,B](val name: String,
               val itemType: String,
               handler: A => B,
               parentLogContext: JournalContext = JournalContext()) extends BoxLike[A,B] {
  def processOneItem(msg: A): B = handler(msg)
  override protected val journalContext: JournalContext = parentLogContext + name

}
