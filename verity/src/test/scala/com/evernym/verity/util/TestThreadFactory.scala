package com.evernym.verity.util

import com.evernym.verity.observability.logs.LoggingUtil

import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicLong

class TestThreadFactory(namePrefix: String, group: ThreadGroup, classLoader: ClassLoader) extends ThreadFactory {
  private val count = new AtomicLong()
  private val logger = LoggingUtil.getLoggerByClass(getClass)

  override def newThread(target: Runnable): Thread = {
    val thread = new Thread(group, new RunnableWithClassLoader(target, classLoader), namePrefix + "-" + count.incrementAndGet)
    logger.info(s"[rg-01] created thread $thread, with contextClassLoader: $classLoader")
    thread
  }
}
class RunnableWithClassLoader(target: Runnable, classLoader: ClassLoader) extends Runnable {
  override def run(): Unit = {
    Thread.currentThread().setContextClassLoader(classLoader)
    target.run()
  }
}

