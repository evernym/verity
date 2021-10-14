package com.evernym.verity.util.healthcheck

import com.evernym.verity.actor.Platform

import scala.concurrent.Future

case class ApiStatus(status: Boolean, msg: String)


trait HealthChecker {
  def checkAkkaEventStorageStatus: Future[ApiStatus]

  def checkWalletStorageStatus: Future[ApiStatus]

  def checkStorageAPIStatus: Future[ApiStatus]

  def checkLedgerPoolStatus: Future[ApiStatus]

  def checkLiveness: Future[Unit]
}

object HealthChecker {
  def apply(platform: Platform): HealthChecker = {
    new HealthCheckerImpl(platform)
  }
}