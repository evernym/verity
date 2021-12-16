package com.evernym.verity.util.healthcheck

import akka.actor.ActorSystem
import com.evernym.verity.actor.agent.AgentActorContext
import com.evernym.verity.vdr.service.VDRTools

import scala.concurrent.{ExecutionContext, Future}

case class ApiStatus(status: Boolean, msg: String)

trait HealthChecker {

  //can be leveldb, dynamodb etc
  def checkAkkaStorageStatus: Future[ApiStatus]

  //can be sqlite, rds etc
  def checkWalletStorageStatus: Future[ApiStatus]

  //can be leveldb, s3 etc
  def checkBlobStorageStatus: Future[ApiStatus]

  def checkLedgerPoolStatus: Future[ApiStatus]

  def checkVDRToolsStatus: Future[ApiStatus]

  def checkLiveness: Future[Unit]
}

object HealthChecker {
  def apply(agentActorContext: AgentActorContext,
            actorSystem: ActorSystem,
            futureExecutionContext: ExecutionContext): HealthChecker = {
    new HealthCheckerImpl(agentActorContext, actorSystem, futureExecutionContext)
  }
}