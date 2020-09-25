package com.evernym.verity.protocol.engine.segmentedstate

import com.evernym.verity.actor.StorageReferenceStored
import com.evernym.verity.protocol.engine.segmentedstate.SegmentedStateTypes._
import com.evernym.verity.protocol.engine._
import com.evernym.verity.util.ParticipantUtil
import scalapb.GeneratedMessage

import scala.util.Try

trait SegmentedStateContext[P,R,M,E,S,I] extends SegmentedStateContextApi { this: ProtocolContext[P,R,M,E,S,I] =>

  val MAX_SEGMENT_SIZE = 400000

  /**
    * pending segment used to save off segment for later persistence at the end of specific transaction
    */
  var pendingSegments: Option[SegmentedStateMsg] = None


  def segmentStoreStrategy: Option[SegmentStoreStrategy]

  def `segmentStoreStrategy_!`: SegmentStoreStrategy = segmentStoreStrategy.getOrElse(
    throw new RuntimeException("segmentStoreStrategy not provided")
  )

  protected def isSegmentRetrievalNeeded(msg: Any): Boolean =
    definition.segmentedStateName.isDefined &&
      definition.segmentRetrieval.isDefinedAt(msg, state)

  def maxSegmentSize(data: GeneratedMessage): Boolean = if (data.serializedSize < MAX_SEGMENT_SIZE) true else false

  private def retrieveSegmentHandler(segmentKey: SegmentKey, segmentAddress: SegmentAddress, msgEnvelope: Any): Either[Any, Option[Any]] => Unit = {
    case Right(Some(storageRef: StorageReferenceStored)) =>
      logger.debug("segment points to OTHER storage")
      handleSegmentedMsgs(ReadStorage(segmentAddress, segmentKey, storageRef), retrieveStorageHandler(msgEnvelope))
    case Right(Some(segment)) =>
      logger.debug("segmented successfully retrieved")
      addToMsgQueue(DataRetrieved())
      addToMsgQueue(MsgWithSegment(msgEnvelope, Some(segment)))
    case Right(None) =>
      logger.debug("no segment found")
      addToMsgQueue(DataNotFound())
      addToMsgQueue(MsgWithSegment(msgEnvelope, None))
    case Left(e) => logger.error("error during retrieving segments: " + e.toString)
  }

  def retrieveStorageHandler(msgEnvelope: Any): Either[Any, Option[Any]] => Unit = {
    case Right(storage) =>
      logger.debug("retrieved storage data")
      addToMsgQueue(DataRetrieved())
      addToMsgQueue(MsgWithSegment(msgEnvelope, storage))
    case Left(e) =>
      logger.error("error retrieving data from storage" + e.toString)
  }

  def getDomainId: DomainId = getBackstate.domainId.getOrElse(ParticipantUtil.DID(getRoster.selfId_!))

  /** retrieves segmented state needed for the incoming message
    *
    * @param msgEnvelope incoming protocol message or control message envelope
    * @param msg incoming protocol message or control message
    */
  def retrieveSegment(msgEnvelope: Any, msg: Any): Unit = {
    val segmentKey = definition.segmentRetrieval(msg, state)
    val segmentId = segmentStoreStrategy_!.calcSegmentId(segmentKey)
    val domainId = Try(getDomainId).getOrElse(withShadowAndRecord(getDomainId))
    val segmentAddress = segmentStoreStrategy_!.calcSegmentAddress(pinstId, domainId, segmentId)
    val segmentedMsg = Read(segmentAddress, segmentKey)
    handleSegmentedMsgs(segmentedMsg, retrieveSegmentHandler(segmentKey, segmentAddress, msgEnvelope))
  }

  def storeSegmentHandler: Either[Any, Option[Any]] => Unit = {
    case Right(Some(Write(address, key, value: StorageReferenceStored))) =>
      handleSegmentedMsgs(Write(address, key, value), storeSegmentHandler)
    case Right(_) =>
      logger.debug("segment successfully stored")
      pendingSegments = None
      finalizeState()
      addToMsgQueue(SegmentStorageComplete())
    case Left(e) =>
      logger.error("error during storing segments: " + e.toString)
      abortTransaction()
      addToMsgQueue(SegmentStorageFailed())
  }

  def storeSegment(segmentKey: SegmentKey, segment: Segment): Unit = {
    val segmentId = segmentStoreStrategy_!.calcSegmentId(segmentKey)
    val domainId = Try(getDomainId).getOrElse(withShadowAndRecord(getDomainId))
    val segmentAddress = segmentStoreStrategy_!.calcSegmentAddress(pinstId, domainId, segmentId)
    pendingSegments = Some(Write(segmentAddress, segmentKey, segment))
  }

  /**
    *
    * @param msg segmented state message (Write or Read)
    * @param postExecution a partial function which excepts either
    *                      (if there is any error during segmented message handling, a Left will be supplied
    *                      if there is no error it can supply Option[Any], mostly this is used in case of Read to
    *                      send back the Segment if found else None)
    */
  def handleSegmentedMsgs(msg: SegmentedStateMsg, postExecution: Either[Any, Option[Any]] => Unit): Unit

  /**
    * purpose of this is to put any given message back to the inbox of the container
    *
    * this is used after retrieving segmented state to put the original message back in the queue,
    * container specific implementation will decide what is the right way to do it
    * for example:
    *  a) in memory protocol container's handleSegmentedMsgs api implementation is synchronous
    *     it can directly put the given msg back to queue by executing 'system.submit'
    *  b) actor protocol container's handleSegmentedMsgs api implementation is asynchronous
    *     it can use 'self ! msg' to put the msg back into the container's queue
    *
    * @param msg the message which needs to be put into the container's mailbox
    */
  def addToMsgQueue(msg: Any): Unit

}

trait SegmentedStateContextApi {
  /**
    * stores given segment with segment key
    * @param segmentKey segment key associated with given segment
    * @param segment segment to be stored
    */
  def storeSegment(segmentKey: SegmentKey, segment: Segment): Unit
}
