package com.evernym.verity.observability.metrics

import akka.testkit.TestKit
import com.evernym.verity.actor.agent.{AgentProvHelper, HasAgentActivity, SponsorRel}
import com.evernym.verity.actor.metrics.activity_tracker.{ActiveRelationships, ActiveUsers, ActivityWindow, ActivityWindowRule, CalendarMonth, VariableDuration}
import com.evernym.verity.actor.testkit.PersistentActorSpec
import com.evernym.verity.did.DidStr
import com.evernym.verity.observability.metrics.MetricHelpers._
import com.evernym.verity.util.{TestExecutionContextProvider, TimeUtil}
import com.evernym.verity.util2.ExecutionContextProvider
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.BeforeAndAfterEach

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration
import scala.util.Try

class ActivityTrackerSpec
  extends PersistentActorSpec
    with HasAgentActivity
    with AgentProvHelper
    with BeforeAndAfterEach {

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
  }

  "An AgentActivityTracker actor" - {

    "with a window of Active User" - {
      "should record activity with multiple users" in {
        val sponsorRel1: SponsorRel = SponsorRel(SPONSOR_ID, SPONSEE_ID)
        val sponsorRel2: SponsorRel = SponsorRel(SPONSOR_ID2, SPONSEE_ID)
        val ea = newEdgeAgent()
        val activityTracker: DidStr = createCloudAgent(sponsorRel1, sponsorKeys().verKey, getNonce, ea)
        val user2: DidStr = createCloudAgent(sponsorRel1, sponsorKeys().verKey, getNonce)
        val user3: DidStr = createCloudAgent(sponsorRel1, sponsorKeys().verKey, getNonce)

        val window15Day = ActivityWindowRule(VariableDuration("15 d"), ActiveUsers)
        val windows = Set(window15Day)

        //Will not increase count for SPONSOR_ID even though the relationship is different because "new" depends on domain id
        AgentActivityTracker.track(DEFAULT_ACTIVITY_TYPE, user3, None)

        //Different Sponsor
        val differentSponsor = createCloudAgent(sponsorRel2, sponsorKeys().verKey, getNonce)
        Thread.sleep(500)
        // Tags for relationships
        val metricKeys = windows.map(_.trackActivityType.metricName)
        val metrics = getMetricWithTags(metricKeys, testMetricsBackend)
        assert(extractTagCount(metrics, window15Day, sponsorRel1.sponsorId) == 3.0)
        assert(extractTagCount(metrics, window15Day, sponsorRel2.sponsorId) == 1.0)
      }

      //Test writes metric with multiple windows
      "should record activity with multiple windows" ignore {
        val sponsorRel3: SponsorRel = SponsorRel(SPONSOR_ID3, SPONSEE_ID)
        val activityTracker: DidStr = createCloudAgent(sponsorRel3, sponsorKeys().verKey, getNonce)
        val baseTimeStamp = TimeUtil.nowDateString
        val windowMonth = ActivityWindowRule(CalendarMonth, ActiveUsers)
        val window30Day = ActivityWindowRule(VariableDuration("30 d"), ActiveUsers)
        val window7Day = ActivityWindowRule(VariableDuration("7 d"), ActiveUsers)
        val windows = Set(windowMonth, window30Day, window7Day)

        AgentActivityTracker.track(DEFAULT_ACTIVITY_TYPE, activityTracker, None)
        Thread.sleep(500)

        /*
          1. Should have metric for each window
           Two Activity messages but only one is recorded (per window).
           The second one is discarded because it doesn't increase any window.
         */
        val metricKeys = windows.map(_.trackActivityType.metricName)
        var metrics = getMetricWithTags(metricKeys, testMetricsBackend)
        assert(extractTagCount(metrics, windowMonth, sponsorRel3.sponsorId) == 1.0)
        assert(extractTagCount(metrics, window30Day, sponsorRel3.sponsorId) == 1.0)
        assert(extractTagCount(metrics, window7Day, sponsorRel3.sponsorId) == 1.0)

        /*
          2. Increase by 7 day
           One Activity message. Only recorded for the 7 day window.
           Both the 30 day and monthly discard it because it doesn't increase window.
         */

        val sevenDayIncrease = TimeUtil.dateAfterDuration(baseTimeStamp, Duration("169 h"))
        AgentActivityTracker.track(DEFAULT_ACTIVITY_TYPE, activityTracker, None, timestamp = sevenDayIncrease)
        Thread.sleep(500)

        metrics = getMetricWithTags(metricKeys, testMetricsBackend)
        assert(extractTagCount(metrics, windowMonth, sponsorRel3.sponsorId) == 1.0)
        assert(extractTagCount(metrics, window30Day, sponsorRel3.sponsorId) == 1.0)
        assert(extractTagCount(metrics, window7Day, sponsorRel3.sponsorId) == 2.0)

        /*
          3. Increase by 30 day
           One Activity message. New activity in both the 7 day window and the 30 day window.
           Still falls within August so monthly discards.
         */
        val thirtyDayIncrease = TimeUtil.dateAfterDuration(baseTimeStamp, Duration(s"${30 * 24 + 1} h"))
        AgentActivityTracker.track(DEFAULT_ACTIVITY_TYPE, activityTracker, relId = None, timestamp = thirtyDayIncrease)
        Thread.sleep(500)

        metrics = getMetricWithTags(metricKeys, testMetricsBackend)
        assert(extractTagCount(metrics, window30Day, sponsorRel3.sponsorId) == 2.0)
        assert(extractTagCount(metrics, window7Day, sponsorRel3.sponsorId) == 3.0)

        /*
          4. Increase by 31 days
           Pushes the timestamp to September.
           should only increase window_monthly
           One Activity message. Both the 7 day and 30 day windows were increased in step #3
             so this new timestamp is 1 day more than the last sent activity timestamp.
         */
        val monthIncrease = TimeUtil.dateAfterDuration(baseTimeStamp, Duration("31 d"))
        AgentActivityTracker.track(DEFAULT_ACTIVITY_TYPE, activityTracker, relId = None, timestamp = monthIncrease)
        Thread.sleep(500)

        metrics = getMetricWithTags(metricKeys, testMetricsBackend)
        assert(extractTagCount(metrics, windowMonth, sponsorRel3.sponsorId) == 2.0)
        assert(extractTagCount(metrics, window30Day, sponsorRel3.sponsorId) == 2.0)
        assert(extractTagCount(metrics, window7Day, sponsorRel3.sponsorId) == 3.0)
      }

      //Test updates window
      "should be able to update window" in {
        val sponsorRel4: SponsorRel = SponsorRel(SPONSOR_ID4, SPONSEE_ID)
        val activityTracker: DidStr = createCloudAgent(sponsorRel4, sponsorKeys().verKey, getNonce)
        val window = ActivityWindowRule(VariableDuration("9 min"), ActiveUsers)
        Thread.sleep(500)
        var metrics = getMetricWithTags(Set(window.trackActivityType.metricName), testMetricsBackend)
        assert(extractTagCount(metrics, window, sponsorRel4.sponsorId) == 1.0)

        val updatedWindow = ActivityWindowRule(VariableDuration("1 d"), ActiveUsers)
        AgentActivityTracker.setWindows(activityTracker, ActivityWindow(Set(updatedWindow)))
        AgentActivityTracker.track(DEFAULT_ACTIVITY_TYPE, activityTracker, None)

        Thread.sleep(500)
        //Show that once the window is updated, an activity will be recorded
        metrics = getMetricWithTags(Set(updatedWindow.trackActivityType.metricName), testMetricsBackend)
        assert(extractTagCount(metrics, updatedWindow, sponsorRel4.sponsorId) == 1.0)
        //Show that old metric window is still available
        assert(extractTagCount(metrics, window, sponsorRel4.sponsorId) == 1.0)
      }
    }

    "with a window of Active Agent Relationship" - {
      "should filter windows when relationship is missing" in {
        val sponsorRel5: SponsorRel = SponsorRel(SPONSOR_ID5, SPONSEE_ID)
        val activityTracker: DidStr = createCloudAgent(sponsorRel5, sponsorKeys().verKey, getNonce)
        val windowMonthRel = ActivityWindowRule(CalendarMonth, ActiveRelationships)
        val window7DayRel = ActivityWindowRule(VariableDuration("7 d"), ActiveRelationships)
        val window2DayUser = ActivityWindowRule(VariableDuration("2 d"), ActiveUsers)
        val windows = Set(windowMonthRel, window7DayRel, window2DayUser)
        val missingRelId: Option[String] = None
        AgentActivityTracker.track(DEFAULT_ACTIVITY_TYPE, activityTracker, missingRelId)

        Thread.sleep(500)

        /*
          1. Should only record for Active Users because relId is missing
         */
        val metricKeys = windows.map(_.trackActivityType.metricName)
        val metrics = getMetricWithTags(metricKeys, testMetricsBackend)
        assert(extractTagCount(metrics, windowMonthRel, activityTracker, missingRelId) == 0.0)
        assert(extractTagCount(metrics, window7DayRel, activityTracker, missingRelId) == 0.0)
        assert(extractTagCount(metrics, window2DayUser, sponsorRel5.sponsorId) == 1.0)
      }

      "should record new activity with multiple relationships" in {
        val sponsorRel6: SponsorRel = SponsorRel(SPONSOR_ID6, SPONSEE_ID)
        val activityTracker: DidStr = createCloudAgent(sponsorRel6, sponsorKeys().verKey, getNonce)
        val baseTimeStamp = TimeUtil.nowDateString
        val windowMonthRel = ActivityWindowRule(CalendarMonth, ActiveRelationships)
        val windows = Set(windowMonthRel)
        AgentActivityTracker.track(DEFAULT_ACTIVITY_TYPE, activityTracker, Some(REL_ID1), timestamp = baseTimeStamp)
        AgentActivityTracker.track(DEFAULT_ACTIVITY_TYPE, activityTracker, Some(REL_ID2), timestamp = baseTimeStamp)
        AgentActivityTracker.track(DEFAULT_ACTIVITY_TYPE, activityTracker, Some(REL_ID3), timestamp = baseTimeStamp)
        var metricKeys = windows.map(_.trackActivityType.metricName)
        Thread.sleep(500) // todo
        var metrics = getMetricWithTags(metricKeys, testMetricsBackend)
        assert(extractTagCount(metrics, windowMonthRel, activityTracker, Some(sponsorRel6.sponseeId)) == 4.0)

        //doesn't add duplicate, same metric number
        AgentActivityTracker.track(DEFAULT_ACTIVITY_TYPE, activityTracker, Some(REL_ID3), timestamp = baseTimeStamp)
        Thread.sleep(500)
        metricKeys = windows.map(_.trackActivityType.metricName)
        metrics = getMetricWithTags(metricKeys, testMetricsBackend)
        assert(extractTagCount(metrics, windowMonthRel, activityTracker, Some(sponsorRel6.sponseeId)) == 4.0)
      }

      //Test writes metric with multiple windows
      "should record activity with multiple windows" ignore {
        val sponsorRel7: SponsorRel = SponsorRel(SPONSOR_ID7, SPONSEE_ID)
        val sponsorRel8: SponsorRel = SponsorRel(SPONSOR_ID8, SPONSEE_ID)
        val activityTracker: DidStr = createCloudAgent(sponsorRel7, sponsorKeys().verKey, getNonce)
        val activityTracker2 = createCloudAgent(sponsorRel8, sponsorKeys().verKey, getNonce)

        val baseTimeStamp = TimeUtil.nowDateString
        val windowMonthRel = ActivityWindowRule(CalendarMonth, ActiveRelationships)
        val window7DayRel = ActivityWindowRule(VariableDuration("7 d"), ActiveRelationships)
        val window3DayUser = ActivityWindowRule(VariableDuration("3 d"), ActiveUsers)
        val windows = Set(windowMonthRel, window7DayRel, window3DayUser)


        AgentActivityTracker.track(DEFAULT_ACTIVITY_TYPE, activityTracker, Some(REL_ID1), timestamp = baseTimeStamp)
        AgentActivityTracker.track(DEFAULT_ACTIVITY_TYPE, activityTracker, Some(REL_ID1), timestamp = baseTimeStamp)
        Thread.sleep(500)

        /*
          1. Should have metric for each window
          Two Activity messages but only one is recorded (per window).
          The second one is discarded because it doesn't increase any window.
         */
        // Tags for relationships
        val metricKeys = windows.map(_.trackActivityType.metricName)
        var metrics = getMetricWithTags(metricKeys, testMetricsBackend)
        assert(extractTagCount(metrics, windowMonthRel, activityTracker, Some(sponsorRel7.sponseeId)) == 2.0)
        assert(extractTagCount(metrics, window7DayRel, activityTracker, Some(sponsorRel7.sponseeId)) == 2.0)

        /*
          2. Increase by 7 day
          One Activity message. Only recorded for the 7 and 3 day window.
          Both the 30 day and monthly discard it because it doesn't increase window.
         */
        val sevenDayIncrease = TimeUtil.dateAfterDuration(baseTimeStamp, Duration("169 h"))
        AgentActivityTracker.track(DEFAULT_ACTIVITY_TYPE, activityTracker, Some(REL_ID1), timestamp = sevenDayIncrease)
        Thread.sleep(500)

        metrics = getMetricWithTags(metricKeys, testMetricsBackend)
        assert(extractTagCount(metrics, windowMonthRel, activityTracker, Some(sponsorRel7.sponseeId)) == 2.0)
        assert(extractTagCount(metrics, window7DayRel, activityTracker, Some(sponsorRel7.sponseeId)) == 3.0)
        assert(extractTagCount(metrics, window3DayUser, sponsorRel7.sponsorId) == 2.0)

        /*
          3. Activity with new domainId - Increase by 90 days
          One Activity message.
          Total count of relationship metrics should change
          User metrics change regardless of the domainId
          Only the tag for domainId will increase
         */
        val threeMonthIncrease = TimeUtil.dateAfterDuration(baseTimeStamp, Duration(s"${90 * 24 + 1} d"))
        AgentActivityTracker.track(DEFAULT_ACTIVITY_TYPE, activityTracker2, Some(REL_ID2), timestamp = threeMonthIncrease)
        Thread.sleep(500)

        metrics = getMetricWithTags(metricKeys, testMetricsBackend)
        assert(extractTagCount(metrics, windowMonthRel, activityTracker, Some(sponsorRel7.sponseeId)) == 2.0)
        assert(extractTagCount(metrics, window7DayRel, activityTracker, Some(sponsorRel7.sponseeId)) == 3.0)
        assert(extractTagCount(metrics, window3DayUser, sponsorRel7.sponsorId) == 2.0)

        /* Tags for relationships
           Original domainId: The previous activity increased the total metric but each tag was not increased
           New domainId: Increased tag
         */
        assert(extractTagCount(metrics, windowMonthRel, activityTracker, Some(sponsorRel7.sponseeId)) == 2.0)
        assert(extractTagCount(metrics, window7DayRel, activityTracker, Some(sponsorRel7.sponseeId)) == 3.0)
        assert(extractTagCount(metrics, windowMonthRel, activityTracker2, Some(sponsorRel8.sponseeId)) == 2.0)
        assert(extractTagCount(metrics, window7DayRel, activityTracker2, Some(sponsorRel8.sponseeId)) == 2.0)
      }
    }
  }

  def extractTagCount(metrics: Map[String, MetricWithTags],
                      window: ActivityWindowRule,
                      id: String,
                      relId: Option[String] = None): Double =
    Try(metrics(window.trackActivityType.metricName)).map(_.tag(window, id, relId)).getOrElse(Some(0.0)).getOrElse(0.0)


  override def overrideSpecificConfig: Option[Config] = Option {
    ConfigFactory parseString {
      s"""
      verity.metrics {
        activity-tracking {
          active-user {
            time-windows = ["15 d", "30 d", "7 d", "1 d", "9 min", "2 d", "3 d"]
            monthly-window = true
            enabled = true
          }
          active-relationships {
            time-windows = ["7 d"]
            monthly-window = true
            enabled = true
          }
        }
      }"""
    }
  }
  /**
   * custom thread pool executor
   */
  lazy val ecp: ExecutionContextProvider = TestExecutionContextProvider.ecp
  override def futureExecutionContext: ExecutionContext = ecp.futureExecutionContext

  override def executionContextProvider: ExecutionContextProvider = ecp
  override def futureWalletExecutionContext: ExecutionContext = ecp.walletFutureExecutionContext
}

case class MetricWithTags(name: String, totalValue: Double, tags: Map[Map[String, String], Double]) {
  def tag(window: ActivityWindowRule, id: String, relId: Option[String] = None): Option[Double] = {
    val baseMap = Map("frequency" -> window.frequencyType.toString, window.trackActivityType.idType -> id)
    val optRelMap = relId.map(x => Map("sponseeId" -> x)).getOrElse(Map.empty) ++ baseMap
    tags.find( _._1.toSet == optRelMap.toSet).map(_._2)
  }
}

object MetricHelpers {
  val SPONSOR_ID: String = "sponsor1.1"
  val SPONSOR_ID2: String = "sponsor2"
  val SPONSOR_ID3: String = "sponsor3"
  val SPONSOR_ID4: String = "sponsor4"
  val SPONSOR_ID5: String = "sponsor5"
  val SPONSOR_ID6: String = "sponsor6"
  val SPONSOR_ID7: String = "sponsor7"
  val SPONSOR_ID8: String = "sponsor8"
  val SPONSEE_ID: String = "sponsee"
  val REL_ID1: String = "rel-1"
  val REL_ID2: String = "rel-2"
  val REL_ID3: String = "rel-3"
  val DEFAULT_ACTIVITY_TYPE: String = "action-taken"

  def getMetricWithTags(names: Set[String], tmw: TestMetricsBackend): Map[String, MetricWithTags] = {
    tmw.allGaugeMetrics()
      .filter(e => names.contains(e._1.name))
      .groupBy(_._1.name)
      .mapValues { m =>
        val total = m.values.sum
        val tags = m.filter(_._1.tags.nonEmpty).map { case (k, v) => k.tags -> v }
        MetricWithTags(m.head._1.name, total, tags)
      }
  }
}
