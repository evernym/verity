package com.evernym.verity.metrics

import java.time.{Duration => JavaDuration}

import akka.testkit.TestKit
import com.evernym.verity.actor.agent.agency.SponsorRel
import com.evernym.verity.actor.metrics._
import com.evernym.verity.actor.testkit.{AkkaTestBasic, PersistentActorSpec, TestAppConfig}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.metrics.ActivityConstants._
import com.evernym.verity.metrics.TestReporter.awaitReport
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.util.TimeUtil
import com.typesafe.config.ConfigFactory
import kamon.tag.TagSet
import org.scalatest.BeforeAndAfterEach

import scala.concurrent.duration.Duration
import scala.util.Try

class ActivityTrackerSpec extends PersistentActorSpec with BasicSpec with BeforeAndAfterEach {

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
  }

  "An AgentActivityTracker actor" - {
    "with a window of Active User" - {
      "should record activity with multiple users" in {
        val baseTimeStamp = "2020-08-01"
        val window15Day = ActiveWindowRules(VariableDuration("15 d"), ActiveUsers)
        val windows = Set(window15Day)
        val activity = AgentActivity(DOMAIN_ID, baseTimeStamp, SPONSOR_REL1, DEFAULT_ACTIVITY_TYPE, None)
        val user1 = system.actorOf(ActivityTracker.props(metricConfig(ActivityWindow(windows))))
        val user2 = system.actorOf(ActivityTracker.props(metricConfig(ActivityWindow(windows))))
        val user3 = system.actorOf(ActivityTracker.props(metricConfig(ActivityWindow(windows))))
        val userDifferentSponsor = system.actorOf(ActivityTracker.props(metricConfig(ActivityWindow(windows))))
        val activityDifferentSponsor = AgentActivity(DOMAIN_ID2, baseTimeStamp, SPONSOR_REL2, DEFAULT_ACTIVITY_TYPE)

        user1 ! activity
        user2 ! activity
        user3 ! activity
        //Will not increase count for SPONSOR_ID even though the relationship is different because "new" depends on domain id
        user3 ! AgentActivity(DOMAIN_ID, baseTimeStamp, SPONSOR_REL1, DEFAULT_ACTIVITY_TYPE, Some(REL_ID2))
        userDifferentSponsor ! activityDifferentSponsor
        Thread.sleep(500)

        // Tags for relationships
        val metricKeys = windows.map(_.activityType.metricBase)
        val metrics = getMetricWithTags(metricKeys)
        assert(extractTagCount(metrics, window15Day, SPONSOR_ID) == 3.0)
        assert(extractTagCount(metrics, window15Day, SPONSOR_ID2) == 1.0)
        assert(metrics(window15Day.activityType.metricBase).totalValue == 4.0)
      }

      //Test writes metric with multiple windows
      "should record activity with multiple windows" in {
        val baseTimeStamp ="2020-08-01"
        val windowMonth = ActiveWindowRules(CalendarMonth, ActiveUsers)
        val window30Day = ActiveWindowRules(VariableDuration("30 d"), ActiveUsers)
        val window7Day = ActiveWindowRules(VariableDuration("7 d"), ActiveUsers)
        val windows = Set(windowMonth, window30Day, window7Day)
        var activity = AgentActivity(DOMAIN_ID, baseTimeStamp, SPONSOR_REL1, DEFAULT_ACTIVITY_TYPE)

        val activityTracker = system.actorOf(ActivityTracker.props(metricConfig(ActivityWindow(windows))))
        activityTracker ! activity
        activityTracker ! activity
        Thread.sleep(500)

        /*
          1. Should have metric for each window
           Two Activity messages but only one is recorded (per window).
           The second one is discarded because it doesn't increase any window.
         */
        val metricKeys = windows.map(_.activityType.metricBase)
        var metrics = getMetricWithTags(metricKeys)
        assert(extractTagCount(metrics, windowMonth, SPONSOR_ID) == 1.0)
        assert(extractTagCount(metrics, window30Day, SPONSOR_ID) == 1.0)
        assert(extractTagCount(metrics, window7Day, SPONSOR_ID) == 1.0)

        /*
          2. Increase by 7 day
           One Activity message. Only recorded for the 7 day window.
           Both the 30 day and monthly discard it because it doesn't increase window.
         */

        val sevenDayIncrease = TimeUtil.dateAfterDuration(baseTimeStamp, Duration("7 d"))
        activity = AgentActivity(DOMAIN_ID, sevenDayIncrease, SPONSOR_REL1, DEFAULT_ACTIVITY_TYPE)
        activityTracker ! activity
        Thread.sleep(500)

        metrics = getMetricWithTags(metricKeys)
        assert(extractTagCount(metrics, windowMonth, SPONSOR_ID) == 1.0)
        assert(extractTagCount(metrics, window30Day, SPONSOR_ID) == 1.0)
        assert(extractTagCount(metrics, window7Day, SPONSOR_ID) == 2.0)

        /*
          3. Increase by 30 day
           One Activity message. New activity in both the 7 day window and the 30 day window.
           Still falls within August so monthly discards.
         */
        val thirtyDayIncrease = TimeUtil.dateAfterDuration(baseTimeStamp, Duration("30 d"))
        activity = AgentActivity(DOMAIN_ID, thirtyDayIncrease, SPONSOR_REL1, DEFAULT_ACTIVITY_TYPE)
        activityTracker ! activity
        Thread.sleep(500)

        metrics = getMetricWithTags(metricKeys)
        assert(extractTagCount(metrics, windowMonth, SPONSOR_ID) == 1.0)
        assert(extractTagCount(metrics, window30Day, SPONSOR_ID) == 2.0)
        assert(extractTagCount(metrics, window7Day, SPONSOR_ID) == 3.0)

        /*
          4. Increase by 31 days
           Pushes the timestamp to September.
           should only increase window_monthly
           One Activity message. Both the 7 day and 30 day windows were increased in step #3
             so this new timestamp is 1 day more than the last sent activity timestamp.
         */
        val monthIncrease = TimeUtil.dateAfterDuration(baseTimeStamp, Duration("31 d"))
        activity = AgentActivity(DOMAIN_ID, monthIncrease, SPONSOR_REL1, DEFAULT_ACTIVITY_TYPE)
        activityTracker ! activity
        Thread.sleep(500)

        metrics = getMetricWithTags(metricKeys)
        assert(extractTagCount(metrics, windowMonth, SPONSOR_ID) == 2.0)
        assert(extractTagCount(metrics, window30Day, SPONSOR_ID) == 2.0)
        assert(extractTagCount(metrics, window7Day, SPONSOR_ID) == 3.0)
      }

      //Test updates window
      "should be able to update window" in {
        val window = ActiveWindowRules(VariableDuration("9 min"), ActiveUsers)
        val activityTracker = system.actorOf(ActivityTracker.props(metricConfig(ActivityWindow(Set(window)))))
        val activity = AgentActivity(DOMAIN_ID, TimeUtil.nowDateString, SPONSOR_REL1, DEFAULT_ACTIVITY_TYPE)
        activityTracker ! activity
        Thread.sleep(500)
        var metrics = getMetricWithTags(Set(window.activityType.metricBase))
        assert(extractTagCount(metrics, window, SPONSOR_ID) == 1.0)

        val updatedWindow = ActiveWindowRules(VariableDuration("1 d"), ActiveUsers)

        activityTracker ! ActivityWindow(Set(updatedWindow))
        activityTracker ! activity
        Thread.sleep(500)
        //Show that once the window is updated, an activity will be recorded
        metrics = getMetricWithTags(Set(updatedWindow.activityType.metricBase))
        assert(extractTagCount(metrics, updatedWindow, SPONSOR_ID) == 1.0)
        //Show that old metric window is still available
        assert(extractTagCount(metrics, window, SPONSOR_ID) == 1.0)
      }
    }

    "with a window of Active Agent Relationship" - {
      "should filter windows when relationship is missing" in {
        val baseTimeStamp = "2020-08-01"
        val windowMonthRel = ActiveWindowRules(CalendarMonth, ActiveRelationships)
        val window7DayRel = ActiveWindowRules(VariableDuration("7 d"), ActiveRelationships)
        val window2DayUser = ActiveWindowRules(VariableDuration("2 d"), ActiveUsers)
        val windows = Set(windowMonthRel, window7DayRel, window2DayUser)
        val missingRelId: Option[String] = None
        val activity = AgentActivity(DOMAIN_ID, baseTimeStamp, SPONSOR_REL1, DEFAULT_ACTIVITY_TYPE, missingRelId)

        val activityTracker = system.actorOf(ActivityTracker.props(metricConfig(ActivityWindow(windows))))
        activityTracker ! activity
        activityTracker ! activity
        Thread.sleep(500)

        /*
          1. Should only record for Active Users because relId is missing
         */
        val metricKeys = windows.map(_.activityType.metricBase)
        val metrics = getMetricWithTags(metricKeys)
        assert(extractTagCount(metrics, windowMonthRel, DOMAIN_ID, missingRelId) == 0.0)
        assert(extractTagCount(metrics, window7DayRel, DOMAIN_ID, missingRelId) == 0.0)
        assert(extractTagCount(metrics, window2DayUser, SPONSOR_REL1.sponsorId) == 1.0)
      }

      "should record new activity with multiple relationships" in {
        val baseTimeStamp = "2020-08-01"
        val windowMonthRel = ActiveWindowRules(CalendarMonth, ActiveRelationships)
        val windows = Set(windowMonthRel)
        val rel1 = AgentActivity(DOMAIN_ID3, baseTimeStamp, SPONSOR_REL2, DEFAULT_ACTIVITY_TYPE, Some(REL_ID1))
        val rel2 = AgentActivity(DOMAIN_ID3, baseTimeStamp, SPONSOR_REL2, DEFAULT_ACTIVITY_TYPE, Some(REL_ID2))
        val rel3 = AgentActivity(DOMAIN_ID3, baseTimeStamp, SPONSOR_REL2, DEFAULT_ACTIVITY_TYPE, Some(REL_ID3))

        val activityTracker = system.actorOf(ActivityTracker.props(metricConfig(ActivityWindow(windows))))
        activityTracker ! rel1
        activityTracker ! rel2
        activityTracker ! rel3
        activityTracker ! rel3
        Thread.sleep(500)

        // Tags for relationships
        val metricKeys = windows.map(_.activityType.metricBase)
        val metrics = getMetricWithTags(metricKeys)
        assert(extractTagCount(metrics, windowMonthRel, DOMAIN_ID3, Some(SPONSOR_REL2.sponseeId)) == 3.0)
      }

      //Test writes metric with multiple windows
      "should record activity with multiple windows" in {
        val baseTimeStamp = "2020-08-01"
        val windowMonthRel = ActiveWindowRules(CalendarMonth, ActiveRelationships)
        val window7DayRel = ActiveWindowRules(VariableDuration("7 d"), ActiveRelationships)
        val window3DayUser = ActiveWindowRules(VariableDuration("3 d"), ActiveUsers)
        val windows = Set(windowMonthRel, window7DayRel, window3DayUser)
        var activity = AgentActivity(DOMAIN_ID, baseTimeStamp, SPONSOR_REL1, DEFAULT_ACTIVITY_TYPE, Some(REL_ID1))

        val activityTracker = system.actorOf(ActivityTracker.props(metricConfig(ActivityWindow(windows))))
        activityTracker ! activity
        activityTracker ! activity
        Thread.sleep(500)

        /*
          1. Should have metric for each window
          Two Activity messages but only one is recorded (per window).
          The second one is discarded because it doesn't increase any window.
         */
        // Tags for relationships
        val metricKeys = windows.map(_.activityType.metricBase)
        var metrics = getMetricWithTags(metricKeys)
        assert(extractTagCount(metrics, windowMonthRel, DOMAIN_ID, Some(SPONSOR_REL1.sponseeId)) == 1.0)
        assert(extractTagCount(metrics, window7DayRel, DOMAIN_ID, Some(SPONSOR_REL1.sponseeId)) == 1.0)

        /*
          2. Increase by 7 day
          One Activity message. Only recorded for the 7 and 3 day window.
          Both the 30 day and monthly discard it because it doesn't increase window.
         */
        val sevenDayIncrease = TimeUtil.dateAfterDuration(baseTimeStamp, Duration("7 d"))
        activity = AgentActivity(DOMAIN_ID, sevenDayIncrease, SPONSOR_REL1, DEFAULT_ACTIVITY_TYPE, Some(REL_ID1))
        activityTracker ! activity
        Thread.sleep(500)

        metrics = getMetricWithTags(metricKeys)
        assert(extractTagCount(metrics, windowMonthRel, DOMAIN_ID, Some(SPONSOR_REL1.sponseeId)) == 1.0)
        assert(extractTagCount(metrics, window7DayRel, DOMAIN_ID, Some(SPONSOR_REL1.sponseeId)) == 2.0)
        assert(extractTagCount(metrics, window3DayUser, SPONSOR_ID) == 2.0)

        /*
          3. Activity with new domainId - Increase by 90 days
          One Activity message.
          Total count of relationship metrics should change
          User metrics change regardless of the domainId
          Only the tag for domainId will increase
         */
        val threeMonthIncrease = TimeUtil.dateAfterDuration(baseTimeStamp, Duration("90 d"))
        val newActivity = AgentActivity(DOMAIN_ID2, threeMonthIncrease, SPONSOR_REL2, DEFAULT_ACTIVITY_TYPE, Some(REL_ID2))
        activityTracker ! newActivity
        Thread.sleep(500)

        metrics = getMetricWithTags(metricKeys)
        assert(extractTagCount(metrics, windowMonthRel, DOMAIN_ID, Some(SPONSOR_REL1.sponseeId)) == 1.0)
        assert(extractTagCount(metrics, window7DayRel, DOMAIN_ID, Some(SPONSOR_REL1.sponseeId)) == 2.0)
        assert(extractTagCount(metrics, window3DayUser, SPONSOR_ID) == 2.0)

        /* Tags for relationships
           Original domainId: The previous activity increased the total metric but each tag was not increased
           New domainId: Increased tag
         */
        assert(extractTagCount(metrics, windowMonthRel, DOMAIN_ID, Some(SPONSOR_REL1.sponseeId)) == 1.0)
        assert(extractTagCount(metrics, window7DayRel, DOMAIN_ID, Some(SPONSOR_REL1.sponseeId)) == 2.0)
        assert(extractTagCount(metrics, windowMonthRel, DOMAIN_ID2, Some(SPONSOR_REL2.sponseeId)) == 1.0)
        assert(extractTagCount(metrics, window7DayRel, DOMAIN_ID2, Some(SPONSOR_REL2.sponseeId)) == 1.0)
      }
    }
  }

  def metricConfig(activityWindow: ActivityWindow): AppConfig = {
    def windowToStr(windows: Set[ActiveWindowRules]): String =
      s"[${windows.filter(_.activityFrequency != CalendarMonth).map(_.activityFrequency.toString).mkString(", ")}]"

    val activeUsers = activityWindow.windows.filter(_.activityType == ActiveUsers)
    val activeRelationship = activityWindow.windows.filter(_.activityType == ActiveRelationships)
    val newConfig =
      s"""
         |activity-tracking {
         |  active-user {
         |    time-windows = ${windowToStr(activeUsers)}
         |    monthly-window = ${activeUsers.exists(_.activityFrequency == CalendarMonth)}
         |    enabled = true
         |  }
         |
         |  active-relationships {
         |    time-windows = ${windowToStr(activeRelationship)}
         |    monthly-window = ${activeRelationship.exists(_.activityFrequency == CalendarMonth)}
         |    enabled = true
         |  }
         |}""".stripMargin

    new TestAppConfig(Some(AkkaTestBasic
      .getConfig()
      .withValue("verity.metrics.activity-tracking", ConfigFactory.parseString(newConfig).getValue("activity-tracking"))))
  }

  def extractTagCount(metrics: Map[String, MetricWithTags],
                      window: ActiveWindowRules,
                      id: String,
                      relId: Option[String]=None): Double =
    Try(metrics(window.activityType.metricBase)).map(_.tag(window, id, relId)).getOrElse(Some(0.0)).getOrElse(0.0)


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
  def tag(window: ActiveWindowRules, id: String, relId: Option[String]=None): Option[Double] = {
    val baseMap = Map( "frequency" -> window.activityFrequency.toString, window.activityType.idType -> id )
    val optRelMap = relId.map(x => Map("sponseeId" -> x)).getOrElse(Map.empty) ++ baseMap
    tags.get(TagSet.from(optRelMap))
  }
}

object ActivityConstants {
  val SPONSOR_ID: String = "sponsor-1"
  val SPONSOR_ID2: String = "sponsor-2"
  val SPONSEE_ID1: String = "sponsee-1"
  val SPONSEE_ID2: String = "sponsee-2"
  val DOMAIN_ID: String = "domain-1"
  val DOMAIN_ID2: String = "domain-2"
  val DOMAIN_ID3: String = "domain-3"
  val REL_ID1: String = "rel-1"
  val REL_ID2: String = "rel-2"
  val REL_ID3: String = "rel-3"
  val DEFAULT_ACTIVITY_TYPE: String = "action-taken"
  val SPONSOR_REL1: SponsorRel = SponsorRel(SPONSOR_ID, SPONSEE_ID1)
  val SPONSOR_REL2: SponsorRel = SponsorRel(SPONSOR_ID2, SPONSEE_ID2)
}
