package com.evernym.verity.util

import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.config.AppConfig
import com.evernym.verity.observability.logs.LoggingUtil

import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicLong

object TestExecutionContextProvider {
  lazy val testAppConfig: AppConfig = new TestAppConfig()
  lazy val ecp: ExecutionContextProvider = new ExecutionContextProvider(testAppConfig, Some(new TestThreadFactory("future-thread-pool", null, getClass.getClassLoader)))
}


class TestThreadFactory(namePrefix: String, group: ThreadGroup, classLoader: ClassLoader) extends ThreadFactory {
  private val count = new AtomicLong()
  private val logger = LoggingUtil.getLoggerByClass(getClass)

  def this(namePrefix: String) {
    this(namePrefix, null, Thread.currentThread().getContextClassLoader)
  }

  override def newThread(target: Runnable): Thread = {
    val thread = new Thread(group, target, namePrefix + "-" + count.incrementAndGet)
    logger.info(s"[rg-01] created thread $thread, with contextClassLoader: $classLoader")
    thread.setContextClassLoader(classLoader)
    logger.info(s"$thread class loader is ${thread.getContextClassLoader}")
    thread
  }
}