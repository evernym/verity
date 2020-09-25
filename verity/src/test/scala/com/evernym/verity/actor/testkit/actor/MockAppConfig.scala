package com.evernym.verity.actor.testkit.actor

import com.evernym.verity.actor.testkit.{AkkaTestBasic, TestAppConfig}
import com.evernym.verity.config.AppConfig

/**
 * unless overridden by implementing class, this is the place where app config
 * for the test will be created
 */
trait MockAppConfig extends OverrideConfig {
  implicit lazy val appConfig: AppConfig = {
    new TestAppConfig(Option(AkkaTestBasic.getConfig(overrideConfig)))
  }
}

trait OverrideConfig {

  /**
   * Allows for a test to provide config parameters that override the default config found in AkkaTestBasic.
   * The withFallback method is used, so the expectation is that overrideConfig includes only those config
   * entries that are being overridden.
   */
  def overrideConfig: Option[String] = None
}