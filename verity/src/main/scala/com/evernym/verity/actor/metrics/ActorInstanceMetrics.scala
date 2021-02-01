//package com.evernym.verity.actor.metrics
//
//import com.evernym.verity.actor.persistence.ActorCommon
//import com.evernym.verity.metrics.CustomMetrics.{TAG_KEY_ID, TAG_KEY_TYPE}
//import com.evernym.verity.metrics.MetricsApi
//import com.evernym.verity.protocol.protocols.HasAppConfig
//
//
///**
// * this is instance (actor instance) based metrics
// * it will by default add instance related tags and so for each unique actor instance,
// * there will be one instance of given metrics
// */
//trait ActorInstanceMetrics { this: ActorCommon with HasAppConfig =>
//
//  object ActorInstanceMetrics {
//    /**
//     * default tags to be attached with every metrics function called in this object
//     *
//     * @return
//     */
//    def tags = Map(TAG_KEY_TYPE -> entityType, TAG_KEY_ID -> entityId)
//    val enabledMetricsConfigKeyPath = "verity.akka.actor.instance-metrics.enabled"
//
//    def updateGauge(name: String, value: Long, withTags: Map[String, String] = Map.empty): Unit = {
//      if (isEnabled(name)) {
//        MetricsApi.gaugeApi.updateWithTags(name, value, withTags ++ tags)
//      } else {
//        log.debug(s"instance metrics not enabled in configuration '$enabledMetricsConfigKeyPath': " + name)
//      }
//    }
//
//    def incrementGauge(name: String, withTags: Map[String, String] = Map.empty): Unit = {
//      if (isEnabled(name)) {
//        MetricsApi.gaugeApi.incrementWithTags(name, withTags ++ tags)
//      } else {
//        log.debug(s"instance metrics not enabled in configuration '$enabledMetricsConfigKeyPath': " + name)
//      }
//    }
//
//    def isEnabled(name: String): Boolean = {
//      appConfig.getConfigListOfStringReq(enabledMetricsConfigKeyPath)
//        .contains(name)
//    }
//  }
//}
