package com.evernym.verity.util

object ObjectSizeUtil {

  import com.twitter.chill.KryoInstantiator
  import com.twitter.chill.KryoPool
  private val POOL_SIZE = 5
  private val kryo: KryoPool = KryoPool.withByteArrayOutputStream(POOL_SIZE, new KryoInstantiator)

  def calcSizeInBytes(obj: AnyRef): Long = {
    kryo.toBytesWithoutClass(obj).length
  }
}
