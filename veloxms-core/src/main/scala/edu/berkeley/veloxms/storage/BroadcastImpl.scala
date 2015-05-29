package edu.berkeley.veloxms.storage

import scala.collection.mutable
import scala.reflect.ClassTag
import scala.util.control.NonFatal

import edu.berkeley.veloxms._
import edu.berkeley.veloxms.util.{Logging, KryoThreadLocal}
import org.apache.spark.SparkContext

trait BroadcastImpl {
  def put(path: String, obj: ByteBuffer, version: Version): Unit
  def get(path: String, version: Version): Option[ByteBuffer] = {
}

class SparkBroadcastImpl(sc: SparkContext, basePath: String)
  extends BroadcastImpl with Logging {

  def put(path: String, obj: ByteBuffer, version: Version): Unit = {
    sc.parallelize(Seq(value)).saveAsObjectFile(s"$basePath/$path/$version")
  }

  def get(path: String, version: Version): Option[ByteBuffer] = {
    val location = s"$basePath/$path/$version"
    try {
      Some(sc.objectFile(location).first())
    } catch {
      case NonFatal(e) =>
        logWarning(s"Failed to load broadcast. ${e.getMessage}")
        None
    }
  }
}
