package com.evernym.verity.integration.base.verity_provider.node

import akka.cluster.MemberStatus
import com.evernym.verity.integration.base.verity_provider.PortProfile
import com.evernym.verity.integration.base.verity_provider.node.local.{ServiceParam, VerityNodeParam}
import com.typesafe.config.Config

import java.nio.file.Path


trait VerityNode {
  def tmpDirPath: Path
  def isAvailable: Boolean
  def appSeed: String
  def portProfile: PortProfile
  def otherNodeArteryPorts: Seq[Int]
  def serviceParam: Option[ServiceParam]
  def overriddenConfig: Option[Config]

  def start(): Unit
  def stop(): Unit

  def restart(): Unit = {
    stop()
    start()
  }

  def checkIfNodeIsUp(otherNodesStatus: Map[VerityNode, List[MemberStatus]] = Map.empty): Boolean

  def verityNodeParam: VerityNodeParam =
    VerityNodeParam (
      tmpDirPath,
      appSeed,
      portProfile,
      otherNodeArteryPorts,
      serviceParam,
      overriddenConfig
    )
}
