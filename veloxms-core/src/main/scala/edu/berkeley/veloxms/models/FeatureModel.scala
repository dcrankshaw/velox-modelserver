package edu.berkeley.veloxms.models


import edu.berkeley.veloxms._
import edu.berkeley.veloxms.storage._
import edu.berkeley.veloxms.util._
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD

trait FeatureModel[T: ClassTag] extends Logging {


  private var version: Version = new Date(0).getTime
  def currentVersion: Version = version
  def useVersion(version: Version): Unit = {
    broadcasts.foreach(_.cache(version))
    this.version = version
  }

  val broadcasts = new ConcurrentLinkedQueue[VersionedBroadcast[_]]()
  protected def broadcast[V: ClassTag](id: String): VersionedBroadcast[V] = {
    // TODO this naming scheme won't work with more than one batch model
    val b = broadcastProvider.get[V](s"$modelName/$id")
    broadcasts.add(b)
    b
  }



  def broadcastProvider: Option[BroadcastProvider] = None

  /** The number of features in this model.
   * Used for pre-allocating arrays/matrices
   */
  def numFeatures: Int

  /**
   */
  protected def computeFeatures(data: T, version: Version) : FeatureVector

  /**
   * Retrains 
   * @param observations
   * @param nextVersion
   * @return
   */
  def trainModel(
      observations: RDD[(UserID, T, Double)],
      nextVersion: Version,
      noRetrain: Boolean = false): RDD[(T, FeatureVector)]

}


