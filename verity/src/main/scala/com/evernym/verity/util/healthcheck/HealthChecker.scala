package com.evernym.verity.util.healthcheck

import akka.actor.ActorSystem
import com.evernym.verity.actor.Platform
import com.evernym.verity.actor.agent.AgentActorContext

import scala.concurrent.{ExecutionContext, Future}

case class ApiStatus(status: Boolean, msg: String)


trait HealthChecker {
  def checkAkkaEventStorageStatus: Future[ApiStatus]

  def checkWalletStorageStatus: Future[ApiStatus]

  def checkStorageAPIStatus: Future[ApiStatus]

  def checkLedgerPoolStatus: Future[ApiStatus]

  def checkLiveness: Future[Unit]
}

object HealthChecker {
  def apply(agentActorContext: AgentActorContext,
            actorSystem: ActorSystem,
            futureExecutionContext: ExecutionContext): HealthChecker = {
    new HealthCheckerImpl(agentActorContext, actorSystem, futureExecutionContext)
  }
}