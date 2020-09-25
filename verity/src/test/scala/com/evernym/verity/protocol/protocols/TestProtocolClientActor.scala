//package com.evernym.verity.protocol
//
//import akka.Done
//import akka.actor.Props
//import com.evernym.verity.AgentActorContext
//import com.evernym.verity.Exceptions._
//import com.evernym.verity.actor._
//import com.evernym.verity.StatusCode._
//import com.evernym.verity.config.ConfigWrapperBase
//
//import scala.concurrent.Future
//
//object TestProtocolClientActor {
//  def props(agentActorContext: AgentActorContext) =
//    Props(new TestProtocolClientActor(agentActorContext))
//}
//
//case class SetInitProtocolBehaviour(letItThrowExceptionOutsideFuture: Boolean = false,
//                                    letItThrowExceptionInFuture: Boolean = false) {
//  require(! (letItThrowExceptionOutsideFuture && letItThrowExceptionInFuture),
//    "'letItThrowExceptionOutsideFuture' and 'letItThrowExceptionInFuture' both can't be set to true")
//}
//
////NOTE: this protocol client actor is similar to UserAgentPairwise actor
//class TestProtocolClientActor(val agentActorContext: AgentActorContext)
//  extends BaseNonPersistentActor
//    with ExceptionHandler
//    with TimeoutSettings
//    with LaunchesProtocol {
//
//  var initProtocolBehaviour = SetInitProtocolBehaviour()
//
//  override lazy val config: ConfigWrapperBase = agentActorContext.config
//
//  override def stateDetailsFor: Future[Set[StateDetail]] = {
//    val values: PartialFunction[String, String] = {
//      case "item-1" => "20"
//      case "item-2" => "30"
//    }
//    if (initProtocolBehaviour.letItThrowExceptionOutsideFuture)
//      throw new NotFoundErrorException(SC_DATA_NOT_FOUND, Option("state not found"))
//    else if (initProtocolBehaviour.letItThrowExceptionInFuture)
//      Future.failed(new NotFoundErrorException(SC_DATA_NOT_FOUND, Option("state not found")))
//    else
//      Future.successful(names.map(n => StateDetail(n, values(n))))
//  }
//
//  override def receiveCmd: Receive = {
//    case ipr: InitProtocolReq =>
//      handleInitProtocolReq(ipr)
//
//    //this command is to test positive and negative scenario around protocol initialization
//    //in real client actor, there won't be any such thing
//    case ipb: SetInitProtocolBehaviour =>
//      initProtocolBehaviour = ipb
//      sender ! Done
//
//    case cmd: Any =>
//      tellProtocol(cmd)
//
//  }
//}
