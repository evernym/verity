package com.evernym.verity.metrics

import com.evernym.verity.metrics.reporter.MetricDetail
import com.evernym.verity.testkit.BasicSpec
import com.typesafe.config.{Config, ConfigFactory}
import kamon.util.Filter
import kamon.util.Filter.SingleMatcher


class MetricsFilterSpec extends BasicSpec {

  lazy val config: Config = {
    ConfigFactory.parseString(
      """
        |verity.metrics.util.filters {
        | "general" {
        |   excludes = [
        |     {"name": "akka_system_active_actors_count*"},
        |     {"value": 0},
        |     {"tags": {"name":"consumer-system"}}
        |   ]
        | }
        |}
        |""".stripMargin
    ).withFallback(ConfigFactory.load("metrics"))
  }

  val metricsFilter: MetricsFilter = new MetricsFilter {
    override def metricsConfig: Config = config
  }

  "MetricsFilter" - {

    "when applied against excluded metrics" - {
      "should filter those metrics" in {
        val metrics = metricsFilter.filterMetrics(List(MetricDetail("akka_system_active_actors_count", "akka_system", 0.0, None)))
        metrics shouldBe List.empty[MetricDetail]
      }
    }

    "when applied against included metrics" - {
      "should keel those metrics" in {
        val metrics = metricsFilter.filterMetrics(List(MetricDetail("akka_group_mailbox_size_sum", "akka_system", 16, None)))
        metrics shouldBe List(MetricDetail("akka_group_mailbox_size_sum", "akka_system", 16, None))
      }
    }

    "when tried get and test IncludeExcludeMatcher" - {
      "should accept metrics according to configuration" in {
        val filters = metricsFilter.fromConfig(config)
        filters shouldBe a[Filters]
        filters.get("general") shouldBe a[IncludeExcludeMatcher]
        filters.get("general").accept(MetricDetail("akka_system_active_actors_count", "akka_system", 0.0, None)) shouldBe false
        filters.get("general").accept(MetricDetail("akka_group_mailbox_size_sum", "akka_system", 16, None)) shouldBe true
      }
    }

    "when tried to test include and exclude filters" - {
      "should be able to read correctly from configuration file" in {
        val filterConfig = config.getConfig("verity.metrics.util.filters")
        val includeFilters = MetricsFilter.readFilters(filterConfig, "general", "includes")
        includeFilters.head shouldBe a[FilterMatcher]
        includeFilters.head.name.get shouldBe a[SingleMatcher]
        includeFilters.head.name.get.accept("akka_group_mailbox_size_sum") shouldBe true
        includeFilters.head.name.get.accept("akka_system_active_actors_count") shouldBe false
        includeFilters.head.name.get.accept("anyRandomString") shouldBe false
        includeFilters.head.value shouldBe None
        includeFilters.head.target shouldBe None
        includeFilters.head.tags shouldBe None

        val excludeFilters = metricsFilter.readFilters(filterConfig, "general", "excludes")
        excludeFilters.head.name.get.accept("akka_system_active_actors_count") shouldBe true
        excludeFilters(1).value.get.accept("0.0") shouldBe true
        excludeFilters(2).tags.get("name") shouldBe a[SingleMatcher]
        excludeFilters(2).tags.get("name").accept("consumer-system") shouldBe true
        excludeFilters(2).tags.get("name").accept("consumer-") shouldBe false

        val randomFilter = metricsFilter.readFilters(filterConfig, "general", "random")
        randomFilter shouldBe Seq.empty
      }
    }

  }

  "FilterMatcher" - {
    "When FilterMatcher has some name" - {
      val filterMatcherWithName = FilterMatcher(Some(Filter.fromGlob("akka_group_mailbox_size_sum")), None, None, None)

      "should return true on matched name" in {
        filterMatcherWithName.accept(MetricDetail("akka_group_mailbox_size_sum", "anyTarget", 0.0, None)) shouldBe true
      }

      "should return false on unmatched name" in {
        filterMatcherWithName.accept(MetricDetail("executor_pool", "anyTarget", 0.0, None)) shouldBe false
      }
    }

    "When FilterMatcher has some value" - {

      "should return true on matched value" in {
        val filterMatcherWithValue = FilterMatcher(None, Some(Filter.fromGlob("0.0")), None, None)
        filterMatcherWithValue.accept(MetricDetail("executor_pool", "anyTarget", 0.0, None)) shouldBe true
      }

      "should return false on unmatched value" in {
        val filterMatcherWithValue = FilterMatcher(None, Some(Filter.fromGlob("0.0")), None, None)
        filterMatcherWithValue.accept(MetricDetail("executor_pool", "anyTarget", 0.1, None)) shouldBe false
      }
    }

    "When FilterMatcher has some target" - {
      val filterMatcherWithTarget = FilterMatcher(None, None, Some(Filter.fromGlob("akka_system")), None)

      "should return true on matched target" in {
        filterMatcherWithTarget.accept(MetricDetail("executor_pool", "akka_system", 0.0, None)) shouldBe true
      }

      "should return false on unmatched target" in {
        filterMatcherWithTarget.accept(MetricDetail("executor_pool", "anyTarget", 0.0, None)) shouldBe false
      }
    }

    "When FilterMatcher has some tags" - {
      val filterMatcherWithTarget = FilterMatcher(None, None, None, Some(Map("name" -> Filter.fromGlob("akka-system"))))

      "should return true on matched tags" in {
        filterMatcherWithTarget.accept(MetricDetail("executor_pool", "akka_system", 0.0, Some(Map("name" -> "akka-system")))) shouldBe true
      }

      "should return false on unmatched tags" in {
        filterMatcherWithTarget.accept(MetricDetail("executor_pool", "anyTarget", 0.0, Some(Map("nam" -> "akka")))) shouldBe false
      }
    }

    "When FilterMatcher has all None" - {
      "should return false for all" in {
       val filterMatcherWithNone =  FilterMatcher(None, None, None, None)
        filterMatcherWithNone.accept(MetricDetail("executor_pool", "anyTarget", 0.0, None)) shouldBe false
      }
    }

    "When FilterMatcher has some & None mixed" - {
      "should return false for all" in {
       val filterMatcherMix =  FilterMatcher(Some(Filter.fromGlob("akka_group_*")), Some(Filter.fromGlob("16.0")), None, None)
        filterMatcherMix.accept(MetricDetail("akka_group_mailbox_size_sum", "anyTarget", 16.0, None)) shouldBe true
      }
    }

  }

  "FilterMatchers" - {
    val filterMatcherWithName = FilterMatcher(Some(Filter.fromGlob("akka_group_mailbox_size_sum")), None, None, None)
    val filterMatcherWithValue = FilterMatcher(None, Some(Filter.fromGlob("16.0")), None, None)
    val filterMatchers = FilterMatchers(Seq(filterMatcherWithName, filterMatcherWithValue))
    "When matches MetricDetail" - {
      "should return true" in {
        filterMatchers.accept(MetricDetail("akka_group_mailbox_size_sum", "anyTarget", 0.0, None)) shouldBe true
        filterMatchers.accept(MetricDetail("anyName", "anyTarget", 16.0, None)) shouldBe true
      }
    }

    "When unmatched MetricDetail" - {
      "should return false" in {
        filterMatchers.accept(MetricDetail("randomName", "anyTarget", 0.0, None)) shouldBe false
      }
    }
  }

  "IncludeExcludeMatcher" - {
    val filterMatcherName1 = FilterMatcher(Some(Filter.fromGlob("akka_group_mailbox_size_sum")), None, None, None)
    val filterMatcherName2 = FilterMatcher(Some(Filter.fromGlob("executor_pool")), None, None, None)

    val filterMatcherWithValue = FilterMatcher(None, Some(Filter.fromGlob("0.0")), None, None)

    val includeFilterMatchers = FilterMatchers(Seq(filterMatcherName1, filterMatcherName2))
    val excludeFilterMatchers = FilterMatchers(Seq(filterMatcherName2, filterMatcherWithValue))

    val includeExcludeMatcher = new IncludeExcludeMatcher(includeFilterMatchers, excludeFilterMatchers)


    "When matched MetricsDetail" - {
      "should return true" in {
        includeExcludeMatcher.accept(MetricDetail("akka_group_mailbox_size_sum", "anyTarget", 16.0, None)) shouldBe true
      }

    "When unmatched MetricDetail" - {
      "should return true" in {
        includeExcludeMatcher.accept(MetricDetail("executor_pool", "anyTarget", 16.0, None)) shouldBe false
        includeExcludeMatcher.accept(MetricDetail("executor_pool", "anyTarget", 0.0, None)) shouldBe false
      }
     }
    }
  }

  "Filters" - {
    val filterMatcherName1 = FilterMatcher(Some(Filter.fromGlob("akka_group_mailbox_size_sum")), None, None, None)
    val filterMatcherName2 = FilterMatcher(Some(Filter.fromGlob("executor_pool")), None, None, None)

    val includeExcludeMatcher = new IncludeExcludeMatcher(FilterMatchers(Seq(filterMatcherName1)), FilterMatchers(Seq(filterMatcherName2)))
    val filetrs = new Filters(Map("general" -> includeExcludeMatcher))

    "should return MetricDetailMatcher correctly" in {
      filetrs.get("general") shouldBe a[MetricDetailMatcher]
      filetrs.get("general") shouldBe includeExcludeMatcher
    }

    "should true for matched MetricDetail" in {
      filetrs.accept("general", MetricDetail("akka_group_mailbox_size_sum", "anyTarget", 0.0, None)) shouldBe true
    }
  }


}
