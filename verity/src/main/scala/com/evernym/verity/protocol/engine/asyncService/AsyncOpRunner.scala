package com.evernym.verity.protocol.engine.asyncService

import scala.collection.mutable
import scala.util.Try

trait AsyncOpRunner {

  def postAllAsyncOpsCompleted()
  def abortTransaction()

  //TODO: it seems we should implement some kind of timeout in this class
  // which does most of the orchestration of running the async operation
  // and expecting container to call 'executeCallbackHandler' function
  // but if container never calls that function then there should be some
  // way to timeout this waiting operation in this class?

  /**
   * implementation to be provided by container
   * @param op the async operation to be executed
   */
  protected def runAsyncOp(op: => Any): Unit

  final def withAsyncOpRunner[T](asyncOp: => Any,
                                 cbHandler: Try[T] => Unit): Unit = {
    pushAsyncOpCallbackHandler(cbHandler)
    runAsyncOp(asyncOp)
  }

  import scala.language.existentials

  protected def executeCallbackHandler[T](asyncOpResp: Try[T]): Any = {
    try {
      val callBackHandler = asyncOpCallbackHandlers.pop()
      //TODO: fix the asInstanceOf in this below line
      callBackHandler.asInstanceOf[AsyncOpCallbackHandler[T]](asyncOpResp)
      postAllAsyncOpsCompleted()
    } catch {
      case e: Exception =>
        abortTransaction(); throw e
    }
  }

  private def pushAsyncOpCallbackHandler[T](cb: AsyncOpCallbackHandler[T]): Unit = {
    asyncOpCallbackHandlers.push(cb)
  }

  protected def popAsyncOpCallBackHandler(): Unit = {
    asyncOpCallbackHandlers.pop()
  }

  protected def resetAllAsyncOpCallBackHandlers(): Unit = {
    asyncOpCallbackHandlers = mutable.Stack[AsyncOpCallbackHandler[_]]()
  }

  /**
   * in progress async operation's callback handlers
   */
  private var asyncOpCallbackHandlers = mutable.Stack[AsyncOpCallbackHandler[_]]()


  /**
   * Things like the url shortener and the wallet and ledger services are internal to a protocol and need to be complete
   *  before segmented state storage and event persistent which are post protocol.
   */
  def isAllAsyncOpsCompleted: Boolean = asyncOpCallbackHandlers.isEmpty
}
