package com.evernym.verity.protocol.engine.asyncapi

import scala.collection.mutable
import scala.util.Try

trait AsyncOpRunner {

  def postAllAsyncOpsCompleted()
  def abortTransaction()

  //TODO: it seems we should implement some kind of timeout in this class
  // which does/expects most of the orchestration of running the async operation
  // and expecting container to call 'executeCallbackHandler' function.
  // But if container never calls 'executeCallbackHandler' function then there should be some
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

  /**
   * once 'runAsyncOp' function is executed and result is available,
   * expectation is that the 'container' will call this (executeCallbackHandler)
   * function with 'asyncOp' result
   *
   * @param asyncOpResp response of async operation
   * @tparam T
   * @return
   */
  protected def executeCallbackHandler[T](asyncOpResp: Try[T]): Any = {
    try {
      val callBackHandler = popAsyncOpCallBackHandler[T]()
      callBackHandler(asyncOpResp)
      postAllAsyncOpsCompleted()
    } catch {
      case e: Exception =>
        abortTransaction(); throw e
    }
  }

  private def pushAsyncOpCallbackHandler[T](cb: AsyncOpCallbackHandler[T]): Unit = {
    asyncOpCallbackHandlers.push(cb)
  }

  protected def popAsyncOpCallBackHandler[T](): AsyncOpCallbackHandler[T] = {
    //TODO: any way to remove 'asInstanceOf' in this function
    asyncOpCallbackHandlers.pop().asInstanceOf[AsyncOpCallbackHandler[T]]
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
