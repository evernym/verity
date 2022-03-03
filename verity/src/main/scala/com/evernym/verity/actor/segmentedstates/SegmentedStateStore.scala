package com.evernym.verity.actor.segmentedstates

import akka.Done
import akka.actor.Props
import com.evernym.verity.actor._
import com.evernym.verity.actor.persistence.object_code_mapper.DefaultObjectCodeMapper
import com.evernym.verity.actor.persistence.{BasePersistentActor, DefaultPersistenceEncryption}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.protocol.engine.ProtoRef
import com.evernym.verity.protocol.engine.segmentedstate.SegmentedStateTypes.SegmentKey
import com.evernym.verity.protocol.protocols.walletBackup.legacy.BackupStored
import com.google.protobuf.ByteString
import scalapb.GeneratedMessage

import scala.concurrent.ExecutionContext


object SegmentedStateStore extends HasProps {

  def buildTypeName(protoRef: ProtoRef): String = {
    s"${protoRef.msgFamilyName}-${protoRef.msgFamilyVersion}-segment"
  }

  def eventCode(event: GeneratedMessage): Int = {
    DefaultObjectCodeMapper.codeFromObject(event)
  }

  def buildEvent(eventCode: Int, data: Array[Byte]): Any = {
    DefaultObjectCodeMapper.objectFromCode(eventCode, data)
  }

  override def props(implicit conf: AppConfig, executionContext: ExecutionContext): Props =  Props(new SegmentedStateStore(conf, executionContext))

  val defaultPassivationTimeout = 600
}

/*
Within a protocol, all the state is normally stored within the protocol
actor itself. But in deaddrop protocol, we didn't want all the state in a single
actor; it's too big. So we created SegmentedState such that each user has their
own subset of the data that can be persisted and managed differently.
 */
class SegmentedStateStore(val appConfig: AppConfig, executionContext: ExecutionContext)
  extends BasePersistentActor
    with DefaultPersistenceEncryption {

  override def futureExecutionContext: ExecutionContext = executionContext

  var state: Map[SegmentKey, Any] = Map.empty

  def receiveCmd: Receive = {
    case SaveSegmentedState(segmentKey, value: StorageReferenceStored) =>
      storeSegment(segmentKey, value)
    case SaveSegmentedState(segmentKey, value: BackupStored) =>
      storeSegment(segmentKey, value)
    case SaveSegmentedState(segmentKey, _) if state.contains(segmentKey) =>
      sender() ! ValidationError("segmented state already stored with segmentKey: " + segmentKey)
    case SaveSegmentedState(segmentKey, value: GeneratedMessage) =>
      storeSegment(segmentKey, value)
    case DeleteSegmentedState(segmentKey) =>
      removeSegment(segmentKey)
    case GetSegmentedState(segmentKey) =>
      sender() ! state.get(segmentKey)
  }

  def storeSegment(key: SegmentKey, value: GeneratedMessage): Unit = {
    val eventCode = SegmentedStateStore.eventCode(value)
    val data = ByteString.copyFrom(value.toByteArray)
    val sss = SegmentedStateStored(key, eventCode, data)
    writeAndApply(sss)
    sender() ! Some(value)
  }

  def removeSegment(key: SegmentKey): Unit = {
    writeAndApply(SegmentedStateRemoved(key))
    sender() ! Done
  }

  def receiveEvent: Receive = {
    case evt: SegmentedStateStored =>
      val dse = SegmentedStateStore.buildEvent(evt.eventCode, evt.data.toByteArray)
      state += (evt.key -> dse)
    case SegmentedStateRemoved(key) =>
      state -= key
  }

}

case class SaveSegmentedState(segmentKey: SegmentKey, value : GeneratedMessage) extends ActorMessage
case class GetSegmentedState(segmentKey: SegmentKey) extends ActorMessage
case class DeleteSegmentedState(segmentKey: SegmentKey) extends ActorMessage
case class ValidationError(error: String) extends ActorMessage