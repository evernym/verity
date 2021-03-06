package com.evernym.verity.protocol.engine

import java.util.UUID

import akka.actor.{Actor, ActorContext, ActorRef, Props}
import akka.util.Timeout
import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.CommonConfig._
import com.evernym.verity.protocol.container.actor.{AsyncAPIContext, AsyncOpResp}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Try}

package object asyncapi {
  type AsyncOpCallbackHandler[T] = Try[T] => Unit
}

trait BaseAsyncAccessImpl {
  implicit val asyncAPIContext: AsyncAPIContext

  lazy val appConfig: AppConfig = asyncAPIContext.appConfig
  lazy val context: ActorContext = asyncAPIContext.senderActorContext

  implicit val timeout: Timeout = asyncAPIContext.timeout
  implicit val senderActorRef: ActorRef = asyncAPIContext.senderActorRef
}

trait BaseAsyncOpExecutorImpl extends BaseAsyncAccessImpl {

  /**
   * this is for those cases where async operation returns Future which consumes fork join pool thread
   * and we want to run it inside an ephemeral actor which uses different thread pool.
   *
   * spins up a new actor to run given async operation which returns future
   * and then sends back the result of the future to the sender (actor protocol container)
   * @param f the async operation to be executed
   * @param handler handler to be executed with the response of async operation
   * @tparam T
   */
  protected def withAsyncOpExecutorActor[T](f: ExecutionContext => Future[Any],
                                              handler: Try[T] => Unit): Unit = {
    val props = AsyncOpExecutorActor
      .props(asyncAPIContext.senderActorRef, f)
      .withDispatcher(ASYNC_OP_EXECUTOR_ACTOR_DISPATCHER_NAME)
    asyncAPIContext.senderActorContext.actorOf(props, s"async-op-executor-" + UUID.randomUUID().toString)
    ()    //purposefully returning unit as the actor will respond with async operation execution result
  }
}


class AsyncOpExecutorActor(senderActorRef: ActorRef, op: ExecutionContext => Future[Any])
  extends Actor {
  import scala.concurrent.ExecutionContextExecutor
  implicit val ex: ExecutionContextExecutor = context.system.dispatchers.lookup(ASYNC_OP_EXECUTOR_ACTOR_DISPATCHER_NAME)
  override def receive: Receive = PartialFunction.empty

  runOp()

  def runOp(): Unit = {
    val result = op(ex)
    result match {
      case f: Future[Any] =>
        f.recover {
          case e: Exception =>
            sendResponse(Failure(e))
        }.onComplete { resp =>
          sendResponse(resp)
        }
    }
  }

  def sendResponse(resp: Try[Any]): Unit = {
    senderActorRef ! AsyncOpResp(resp)
    context.stop(self)
  }
}

object AsyncOpExecutorActor {
  def props(senderActorRef: ActorRef, op: ExecutionContext => Future[Any]): Props =
    Props(new AsyncOpExecutorActor(senderActorRef, op))
}
