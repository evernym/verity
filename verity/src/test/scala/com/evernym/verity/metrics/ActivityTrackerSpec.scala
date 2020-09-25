package com.evernym.verity.metrics

import java.time.{Duration => JavaDuration}

import akka.testkit.TestKit
import com.evernym.verity.actor.metrics._
import com.evernym.verity.actor.testkit.PersistentActorSpec
import com.evernym.verity.metrics.ActivityConstants._
import com.evernym.verity.metrics.CustomMetrics.{AS_ACTIVE_USER_AGENT_COUNT, AS_USER_AGENT_ACTIVE_RELATIONSHIPS}
import com.evernym.verity.metrics.TestReporter.awaitReport
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.util.TimeUtil
import kamon.tag.TagSet
import org.scalatest.BeforeAndAfterEach

import scala.concurrent.duration.Duration

class ActivityTrackerSpec extends PersistentActorSpec with BasicSpec with BeforeAndAfterEach {

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
  }

  "An AgentActivityTracker actor" - {
    "with a window of Active User" - {

      "should test metric key" in {
        def genKey(duration: FrequencyType, activityType: Behavior): String =
          ActiveWindowRules(duration, activityType).metricKey

        //Active User Monthly
        assert(genKey(CalendarMonth, ActiveUsers).equals(s"$AS_ACTIVE_USER_AGENT_COUNT.ActiveUsers.Monthly"))

        //Active User Duration
        assert(genKey(VariableDuration(Duration("3 d")), ActiveUsers).equals(s"$AS_ACTIVE_USER_AGENT_COUNT.ActiveUsers.3 days"))

        //Active Relationship Duration
        assert(genKey(VariableDuration(Duration("5 min")), ActiveRelationships).equals(s"$AS_USER_AGENT_ACTIVE_RELATIONSHIPS.ActiveRelationships.5 minutes"))

        //Active Relationship Monthly
        assert(genKey(CalendarMonth, ActiveRelationships).equals(s"$AS_USER_AGENT_ACTIVE_RELATIONSHIPS.ActiveRelationships.Monthly"))
      }

      //Test writes metric with multiple windows
      "should record activity with multiple windows" in {
        val baseTimeStamp ="2020-08-01"
        val window_month = ActiveWindowRules(CalendarMonth, ActiveUsers)
        val window_30_day = ActiveWindowRules(VariableDuration(Duration("30 d")), ActiveUsers)
        val window_7_day = ActiveWindowRules(VariableDuration(Duration("7 d")), ActiveUsers)
        val windows = Set(window_month, window_30_day, window_7_day)
        var activity = AgentActivity(DOMAIN_ID, baseTimeStamp, SPONSOR_ID, DEFAULT_ACTIVITY_TYPE)

        val activityTracker = system.actorOf(ActivityTracker.props(appConfig, ActivityWindow(windows)))
        activityTracker ! activity
        activityTracker ! activity
        Thread.sleep(500)

        /*
          1. Should have metric for each window
           Two Activity messages but only one is recorded (per window).
           The second one is discarded because it doesn't increase any window.
         */
        val metricKeys = windows.map(_.metricKey)
        var metrics = getMetricWithTags(metricKeys)
        assert(metrics(window_month.metricKey).totalValue == 1.0)
        assert(metrics(window_30_day.metricKey).totalValue == 1.0)
        assert(metrics(window_7_day.metricKey).totalValue == 1.0)

        /*
          2. Increase by 7 day
           One Activity message. Only recorded for the 7 day window.
           Both the 30 day and monthly discard it because it doesn't increase window.
         */

        val sevenDayIncrease = TimeUtil.dateAfterDuration(baseTimeStamp, Duration("7 d"))
        activity = AgentActivity(DOMAIN_ID, sevenDayIncrease, SPONSOR_ID, DEFAULT_ACTIVITY_TYPE)
        activityTracker ! activity
        Thread.sleep(500)

        metrics = getMetricWithTags(metricKeys)
        assert(metrics(window_month.metricKey).totalValue == 1.0)
        assert(metrics(window_30_day.metricKey).totalValue == 1.0)
        assert(metrics(window_7_day.metricKey).totalValue == 2.0)

        /*
          3. Increase by 30 day
           One Activity message. New activity in both the 7 day window and the 30 day window.
           Still falls within August so monthly discards.
         */
        val thirtyDayIncrease = TimeUtil.dateAfterDuration(baseTimeStamp, Duration("30 d"))
        activity = AgentActivity(DOMAIN_ID, thirtyDayIncrease, SPONSOR_ID, DEFAULT_ACTIVITY_TYPE)
        activityTracker ! activity
        Thread.sleep(500)

        metrics = getMetricWithTags(metricKeys)
        assert(metrics(window_month.metricKey).totalValue == 1.0)
        assert(metrics(window_30_day.metricKey).totalValue == 2.0)
        assert(metrics(window_7_day.metricKey).totalValue == 3.0)

        /*
          4. Increase by 31 days
           Pushes the timestamp to September.
           should only increase window_monthly
           One Activity message. Both the 7 day and 30 day windows were increased in step #3
             so this new timestamp is 1 day more than the last sent activity timestamp.
         */
        val monthIncrease = TimeUtil.dateAfterDuration(baseTimeStamp, Duration("31 d"))
        activity = AgentActivity(DOMAIN_ID, monthIncrease, SPONSOR_ID, DEFAULT_ACTIVITY_TYPE)
        activityTracker ! activity
        Thread.sleep(500)

        metrics = getMetricWithTags(metricKeys)
        assert(metrics(window_month.metricKey).totalValue == 2.0)
        assert(metrics(window_30_day.metricKey).totalValue == 2.0)
        assert(metrics(window_7_day.metricKey).totalValue == 3.0)
      }

      //Test updates window
      "should be able to update window" in {
        val window = ActiveWindowRules(VariableDuration(Duration("9 min")), ActiveUsers)
        val activityTracker = system.actorOf(ActivityTracker.props(appConfig, ActivityWindow(Set(window))))
        val activity = AgentActivity(DOMAIN_ID, TimeUtil.nowDateString, SPONSOR_ID, DEFAULT_ACTIVITY_TYPE)
        activityTracker ! activity
        Thread.sleep(500)
        assert(getMetricWithTags(Set(window.metricKey))(window.metricKey).totalValue == 1.0)

        val updatedWindow = ActiveWindowRules(VariableDuration(Duration("1 d")), ActiveUsers)
        //Make sure metric is empty before update
        assert(!getMetricWithTags(Set(updatedWindow.metricKey)).contains(updatedWindow.metricKey))

        activityTracker ! ActivityWindow(Set(updatedWindow))
        activityTracker ! activity
        Thread.sleep(500)
        //Show that once the window is updated, an activity will be recorded
        assert(getMetricWithTags(Set(updatedWindow.metricKey))(updatedWindow.metricKey).totalValue == 1.0)
        //Show that old metric window is still available
        assert(getMetricWithTags(Set(window.metricKey))(window.metricKey).totalValue == 1.0)
      }
    }

    "with a window of Active Agent Relationship" - {
      //Test writes metric with multiple windows
      "should record activity with multiple windows" in {
        val baseTimeStamp = "2020-08-01"
        val window_month_rel = ActiveWindowRules(CalendarMonth, ActiveRelationships)
        val window_7_day_rel = ActiveWindowRules(VariableDuration(Duration("7 d")), ActiveRelationships)
        val window_3_day_users = ActiveWindowRules(VariableDuration(Duration("3 d")), ActiveUsers)
        val windows = Set(window_month_rel, window_7_day_rel, window_3_day_users)
        var activity = AgentActivity(DOMAIN_ID, baseTimeStamp, SPONSOR_ID, DEFAULT_ACTIVITY_TYPE)

        val activityTracker = system.actorOf(ActivityTracker.props(appConfig, ActivityWindow(windows)))
        activityTracker ! activity
        activityTracker ! activity
        Thread.sleep(500)

        /*
          1. Should have metric for each window
          Two Activity messages but only one is recorded (per window).
          The second one is discarded because it doesn't increase any window.
         */
        val metricKeys = windows.map(_.metricKey)
        var metrics = getMetricWithTags(metricKeys)
        assert(metrics(window_month_rel.metricKey).totalValue == 1.0)
        assert(metrics(window_7_day_rel.metricKey).totalValue == 1.0)
        assert(metrics(window_3_day_users.metricKey).totalValue == 1.0)
        // Tags for relationships
        assert(metrics(window_month_rel.metricKey).tag("domainId", DOMAIN_ID).get == 1.0)
        assert(metrics(window_7_day_rel.metricKey).tag("domainId", DOMAIN_ID).get == 1.0)

        /*
          2. Increase by 7 day
          One Activity message. Only recorded for the 7 day window.
          Both the 30 day and monthly discard it because it doesn't increase window.
         */
        val sevenDayIncrease = TimeUtil.dateAfterDuration(baseTimeStamp, Duration("7 d"))
        activity = AgentActivity(DOMAIN_ID, sevenDayIncrease, SPONSOR_ID, DEFAULT_ACTIVITY_TYPE)
        activityTracker ! activity
        Thread.sleep(500)

        metrics = getMetricWithTags(metricKeys)
        assert(metrics(window_month_rel.metricKey).totalValue == 1.0)
        assert(metrics(window_7_day_rel.metricKey).totalValue == 2.0)
        assert(metrics(window_3_day_users.metricKey).totalValue == 2.0)
        // Tags for relationships
        assert(metrics(window_month_rel.metricKey).tag("domainId", DOMAIN_ID).get == 1.0)
        assert(metrics(window_7_day_rel.metricKey).tag("domainId", DOMAIN_ID).get == 2.0)

        /*
          3. Activity with new domainId - Increase by 90 days
          One Activity message.
          Total count of relationship metrics should change
          User metrics change regardless of the domainId
          Only the tag for domainId will increase
         */
        val threeMonthIncrease = TimeUtil.dateAfterDuration(baseTimeStamp, Duration("90 d"))
        val newActivity = AgentActivity(DOMAIN_ID2, threeMonthIncrease, SPONSOR_ID2, DEFAULT_ACTIVITY_TYPE)
        activityTracker ! newActivity
        Thread.sleep(500)

        metrics = getMetricWithTags(metricKeys)
        assert(metrics(window_month_rel.metricKey).totalValue == 2.0)
        assert(metrics(window_7_day_rel.metricKey).totalValue == 3.0)
        assert(metrics(window_3_day_users.metricKey).totalValue == 3.0)
        /* Tags for relationships
           Original domainId: The previous activity increased the total metric but each tag was not increased
           New domainId: Increased tag
         */
        assert(metrics(window_month_rel.metricKey).tag("domainId", DOMAIN_ID).get == 1.0)
        assert(metrics(window_7_day_rel.metricKey).tag("domainId", DOMAIN_ID).get == 2.0)
        assert(metrics(window_month_rel.metricKey).tag("domainId", DOMAIN_ID2).get == 1.0)
        assert(metrics(window_7_day_rel.metricKey).tag("domainId", DOMAIN_ID2).get == 1.0)
      }
    }
  }

  def getMetricWithTags(names: Set[String]): Map[String, MetricWithTags] = {
    val report = awaitReport(JavaDuration.ofSeconds(5))
    assert(report != null)
    report
      .gauges
      .filter(x => names.contains(x.name))
      .map(g => g.name -> {
        val totalMetricCount = g.instruments.map(_.value).sum
        val tags = g.instruments
          .filter(!_.tags.isEmpty())
          .map(x => x.tags -> x.value)
          .toMap

        MetricWithTags( g.name, totalMetricCount, tags)
      }).toMap
  }
}

case class MetricWithTags(name: String, totalValue: Double, tags: Map[TagSet, Double]) {
  def tag(tagType: String, id: String): Option[Double] = tags.get(TagSet.of(tagType, id))
}

object ActivityConstants {
  val SPONSOR_ID: String = "sponsor-1"
  val SPONSOR_ID2: String = "sponsor-2"
  val DOMAIN_ID: String = "domain-1"
  val DOMAIN_ID2: String = "domain-2"
  val DEFAULT_ACTIVITY_TYPE: String = "action-taken"
}
