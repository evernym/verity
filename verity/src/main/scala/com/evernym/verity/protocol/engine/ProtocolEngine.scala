package com.evernym.verity.protocol.engine

import com.evernym.verity.protocol.protocols.connecting.v_0_5.ConnectingProtoDef

abstract class Handler() {
  def handle()
}

class ProtocolEngine(val signalMsgHandler: String => Unit,
                     val msgSender: String => Unit,
                     val eventRecorder: String => Unit) extends LaunchesProtocol {

  override def contextualId: Option[String] = None //TODO: complete this
  def domainId: DomainId = "placeholder for now" //TODO: complete this
  def relationshipId: Option[String] = None   //TODO: complete this

  val protocolRegistry = ProtocolRegistry(ConnectingProtoDef -> PinstIdResolution.V0_1)

//  def handleMsg(msg: Any): Boolean = {
//    var handled = sendMsgToProtocolContainers(msg)
//    if(!handled) {
//      handled = startProtocolsWithMsg(msg)
//    }
//    handled





//package com.evernym.verity.protocol.engine
//
//import com.evernym.verity.protocol.SegmentedStateMsg
//
//import scala.concurrent.Future
//
//class ProtocolEngine[UiType]()
////                     val signalMsgHandler: String => Unit,
////                     val msgSender: String => Unit,
////                     val eventRecorder: String => Unit)
//{
//
//  type ExtensionType = Extension[ControllerGenParam, UiGen]
//  type Container = BaseProtocolContainer[_,_,_,_,_,_]
//  type ControllerGen = ControllerGenParam => Driver
//  type UiGen = () => UiType
//  case class ControllerGenParam(engine: ProtocolEngine[UiType], ui: UiType)
//
//  private var extensionRegistry: Map[ProtoRef, ExtensionType] = Map.empty
//
//  private var containers: Map[PinstId, Container] = Map.empty //reconcile with SimpleProtocolSystem
//
//  def registerExtension(ext: ExtensionType): Unit = {
//    extensionRegistry = extensionRegistry + (ext.protoDef.msgFamily.protoRef -> ext)
//  }
//
//  private def extension_!(protoRef: ProtoRef): ExtensionType = {
//    extensionRegistry.getOrElse(protoRef, throw new RuntimeException(s"Protocol not registered: $protoRef"))
//  }
//
//  def handleMsg(pinstId: PinstId, msg: Any): Any = {
//    val container = containers.getOrElse(pinstId, throw new RuntimeException(s"unknown protocol instance id: $pinstId"))
//    container.handleMsg(msg)
//  }
//
//  def handleMsg(safeThreadId: String, protoRef: ProtoRef, msg: Any): Any = {
//    val pinstId = calcPinstId(safeThreadId, protoRef, msg)
//    val container = containers.getOrElse(pinstId, createContainer(pinstId, extension_!(protoRef)))
//    container.handleMsg(msg)
//  }
//
//  private def calcPinstId(safeThreadId: String, protoRef: ProtoRef, msg: Any): PinstId = {
//    safeThreadId //do this for now, reconcile with other protocol systems
//  }
//
//  class BaseProtocolContainer[P,R,M,E,S,I](val pinstId: PinstId,
//                                           val definition: ProtocolDefinition[P,R,M,E,S,I],
//                                           driver: Driver) extends ProtocolContainer[P,R,M,E,S,I] {
//
//    override def eventRecorder: RecordsEvents = ???
//    override def sendsMsgs: SendsMsgs = ???
//    override def driver: Option[Driver] = Option(driver)
//    override def createServices: Option[Services] = ???
//    override def requestInit(): Unit = ???
//    override def handleSegmentedMsgs[T](msg: SegmentedStateMsg): Future[Option[T]] = ???
//  }
//
//  private def createContainer[P,R,M,E,S,I](pinstId: PinstId,
//                                           ext: ExtensionType): Container = {
//    new BaseProtocolContainer(pinstId, ext.protoDef, ext.driverGen(ControllerGenParam(this, ext.uiGen()())))
//  }
//
//  //  val contextId = "placeholder for now" //TODO: complete this
//
////  private def sendMsgToProtocolContainers(msg: Any): Boolean = {
////    protocolContainers.find(_.handles(msg)).foreach(_.handleMsg(msg)).exists
////
////  private def startProtocolsWithMsg(msg: Any): Boolean = {
////    for(supportedProtocol <- supportedProtocols) {
////      if(supportedProtocol.isInitMessage(msg)) {
////        container = new InMemoryProtocolContainer[_, _, _, _, _, _]() // TODO: complete this: How do I extract these types from the protocolDefinition?
////        protocolContainers += container
////        return true
////      }
////    }
////    false
////  }
////
////  @JSExport
////  def handleControlMsg(msg: Any): Unit = {
////    // Do control messages need to be passed to protocols differently then protocol messages?
////    // Can we make it so that control messages have the same protocol entrypoint as protocol messages?
////    // The protocol should recognize whether or not a message is a control message
////    println("control message handled")
////  }
////
////  @JSExport
////  def test_sendSignalMsg(): Unit = { // TODO: complete this: Delete this function. Used for JS testing only right now.
////    signalMsgHandler.call("none", "hello")
////  }
}
