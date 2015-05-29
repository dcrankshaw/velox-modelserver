package edu.berkeley.veloxms.storage

import edu.berkeley.veloxms.Version
import java.nio.ByteBuffer
import edu.berkeley.veloxms.util.KryoThreadLocal

/**
 * Thread-safe versioned variable broadcasting
 */
class VersionedBroadcast[T](name: String) {

  var broadcastImpl: BroadcastImpl = null
  private var initialized = false

  def init(b: BroadcastImpl): Unit = this.synchronized {
    require(init == false, s"Broadcast variable $name already initialized")
    this.initialized = true
    broadcastImpl = b
  }

  private val cachedValues: mutable.Map[Version, T] = mutable.Map()

  def get(version: Version): Option[T] = this.synchronized {
    val out = cachedValues.get(version).orElse(fetch(version))
    out.foreach(x => cachedValues.put(version, x))
    out
  }

  def put(value: T, version: Version): Unit = this.synchronized {
    val kryo = KryoThreadLocal.kryoTL.get
    val out = kryo.serialize(value)
    broadcastImpl.put(name, out, version)
  }
  // Tries to fetch & cache the version. Any pre-existing cached value for the version will be invalidated
  def cache(version: Version): Unit = this.synchronized {
    fetch(version).foreach(x => cachedValues.put(version, x))
  }


  private def fetch(version: Version): Option[T] = {
    try {
      Some(broadcastImpl.get(name, version)
    } catch {
      case NonFatal(e) =>
        logWarning(s"Failed to load broadcast. ${e.getMessage}")
        None
    }
  }
}

object VersionedBroadcast[T] {
  def apply(name: String) = new VersionedBroadcast(name)
}
