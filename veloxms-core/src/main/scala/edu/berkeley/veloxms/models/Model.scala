
package edu.berkeley.veloxms.models

import java.util.Date
import java.util.concurrent.{ConcurrentHashMap, ConcurrentLinkedQueue}

import edu.berkeley.veloxms._
import edu.berkeley.veloxms.storage._
import edu.berkeley.veloxms.util._
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD

import scala.collection.JavaConversions._
import scala.reflect._
import scala.util.Sorting
import scala.collection.mutable


/**
 * Model interface
 * @tparam T The scala type of the item, deserialized from Array[Byte]
 * We defer deserialization to the model interface to encapsulate everything
 * the user must implement into a single class.
 */
class Model[T: ClassTag](broadcastProvider: BroadcastProvider) extends Logging {

  // private var featureModels = new Vector[FeatureModel[T]]()
  private var featureModels = Vector.empty

  // associate a tag with each feature model to allow for
  // independent retraining
  private var modelTags = Vector.empty
  private var numFeatures = 0
  private var onlineUpdateManager: Option[OnlineUpdateManager] = None
  private var broadcastProvider: Option[BroadcastProvider] = None

  def setOnlineUpdateManager(manager: OnlineUpdateManager): Unit = {
    onlineUpdateManager = Some(manager)
  }

  def setBroadcastProvider(bp: BroadcastProvider): Unit = {
    broadcastProvider = Some(BroadcastProvider)
  }

  /**
   * Add a new set of features to this model. This is
   * threadsafe to allow for adding new features at runtime.
   */
  def addFeatureModel(tag: String, model: FeatureModel[T]): Unit = {
    this.synchronized {
      featureModels = featureModels :+ model
      modelTags = modelTags :+ tag
      // featureModels.put(name, model) match {
      //   case Some(oldModel) => logInfo(s"Overwriting old feature model with name: \"$name\"")
      //   case None => logInfo(s"Added feature model with name: \"$name\"")
      // }
      numFeatures += model.numFeatures
      model.initBroadcast(broadcastProvider)
    }
  }


  // TODO(crankshaw): make sure that this can actually be set
  val modelName: String
  def setModelName(n: String): Unit = { modelName = n }

  // TODO: Observations should be stored w/ Timestamps, and in a more relational format w/ persistence
  val observations: ConcurrentHashMap[UserID, ConcurrentHashMap[T, Double]] = new ConcurrentHashMap()
  val userWeights: ConcurrentHashMap[(UserID, Version), WeightVector] = new ConcurrentHashMap()


  /** Average user weight vector.
   * Used for warmstart for new users
   * TODO: SHOULD BE RETRAINED WHEN BULK RETRAINING!!!
   **/
  var averageUser: WeightVector

  /**
   * Concatenates features from all registered feature models
   */
  def computeFeatures(data: T, version: Version) : FeatureVector {

    // Use fold instead of reduce in case there are no models registered
    featureModels.map(_.computeFeatures(data, version).foldLeft(FeatureVector)(_ ++ _)
  }


  /**
   * Retrain the listed feature models in Spark
   * @param modelName the tags of each model to be trained
   *                  (allows for only retraining a subset of the models)
   * @param observations all of the training data Velox has collected for all users
   * @param nextVersion system provided version number to increment the model version to
   * @returns an RDD containing the features of all items in the observation
   *          training dataset
   */
  def trainFeatureModels(
      modelNames: Seq[String],
      observations: RDD[(UserID, T, Double)],
      nextVersion: Version): RDD[(T, FeatureVector)]  {

    val retrainModels = featureModels.filterKeys(n => modelNames.contains(n)).values

    // This performs batch training sequentially, does it make sense to
    // run these concurrently on the same Spark cluster?
    val itemFeaturesList = (modelTags, featureModels).zipped.map( {case(tag, model) => {
        if (modelNames.contains(tag)) {
          model.trainModel(observations, nextVersion)
        } else {
          // hint to the feature model that it doesn't need to retrain
          model.trainModel(observations, nextVersion, true)
        }
      }
    })

    // join the two RDDs on item then concatenate the feature vectors from
    // the two feature models for each item
    val joinFeatureVectors = (f1, f2) => {
      f1.join(f2).map( {case (item, (vec1, vec2)) => (item, vec1 ++ vec2)})
    }
    itemFeaturesList.reduceLeft(joinFeatureVectors)
  }

  def trainAllFeatureModels(
      observations: RDD[(UserID, T, Double)],
      nextVersion: Version): RDD[(T, FeatureVector)] {
    trainFeatureModels(featureModels.keys, observations, nextVersion)

  }


  /**
   * Retrains 
   * @param observations
   * @param nextVersion
   * @return
   */
  final def retrainUserWeightsInSpark(
      itemFeatures: RDD[(T, FeatureVector)],
      observations: RDD[(UserID, T, Double)])
    : RDD[(UserID, WeightVector)] = {
    val obs = observations.map(x => (x._2, (x._1, x._3)))
    val ratings = itemFeatures.join(obs).map(x => (x._2._2._1, x._2._1, x._2._2._2))
    UserWeightUpdateMethods.calculateMultiUserWeights(ratings, numFeatures)
  }

  /**
   * Calculates the optimal cold-start user model for the case when we have
   * no training data for a user.
   *
   * Implementation: Take the average score for each item across the
   * entire training data set and use that as the training data for
   * the average user model.
   */
  final def retrainAvgUserInSpark(
      itemFeatures: RDD[(T, FeatureVector)],
      observations: RDD[(UserID, T, Double)]): WeightVector = {
    // drop user id from observations
    val avgObservations = observations
        .map(o => (o._2, o._3))
        .groupByKey()
        .map( {case(item, scores) => (item, (scores.reduce(_+_) / scores.size))})
        .join(itemFeatures).map({case(item, (score, features)) => (features, score)})
        .collect()
    UserWeightUpdateMethods.calculateSingleUserWeights(avgObservations, numFeatures)
  }

  /**
   * Velox implemented - fetch from local Tachyon partition
   *
   */
  private def getWeightVector(userId: Long, version: Version) : WeightVector = {
    val result: Option[Array[Double]] = Option(userWeights.get((userId, version)))
    result match {
      case Some(u) => u
      case None => {
        logWarning(s"User weight not found, userID: $userId")
        averageUser
      }
    }
  }

  def predict(uid: UserID, item: T, version: Version): Double = {
    val features = computeFeatures(item, version)
    val weightVector = getWeightVector(uid, version)
    var i = 0
    var score = 0.0
    // it's possible that new feature models have been added since the
    // last time this user model was updated, in which case we want to ignore
    // the new features for now (equivalent to setting the weights to 0
    while (i < weightVector.size) {
      score += features(i) * weightVector(i)
      i += 1
    }
    // check to see if new feature models have been added
    if (weightVector.size < numFeatures && !onlineUpdateManager.isEmpty) {
      onlineUpdateManager.get.addUserToQueue(uid)

    }
    score
  }

  def predictTopK(uid: Long, k: Int, candidateSet: Array[T], version: Version): Array[T] = {
    // FIXME: There is probably some threshhold of k for which it makes more sense to iterate over the unsorted list
    // instead of sorting the whole list.
    val itemOrdering = new Ordering[T] {
      override def compare(x: T, y: T) = {
        -1 * (predict(uid, x, version) compare predict(uid, y, version))
      }
    }

    Sorting.quickSort(candidateSet)(itemOrdering)
    candidateSet.slice(0, k)
  }

  final def addObservation(uid: UserID, item: T, score: Double) {
    observations.putIfAbsent(uid, new ConcurrentHashMap())
    val scores = observations.get(uid)
    scores.put(item, score)
  }

  final def addObservations(observations: TraversableOnce[(UserID, T, Double)]): Unit = {
    observations.foreach(o => addObservation(o._1, o._2, o._3))
  }

  final def getObservationsAsRDD(sc: SparkContext): RDD[(UserID, T, Double)] = {
    val x = observations.flatMap({ case (user, obs) =>
      obs.map { case (item, score) => (user, item, score) }
    }).toArray
    if (x.length > 0) {
      sc.parallelize(x)
    } else {
      // This is done because parallelize of an empty seq errors and Spark EmptyRDD is private
      sc.parallelize[(UserID, T, Double)](Seq(null)).filter(_ => false)
    }
  }

  final def updateUser(uid: UserID, version: Version): Unit = {
    val ratings = Some(observations.get(uid)).getOrElse(new ConcurrentHashMap()).map {
      case (c, s) => (computeFeatures(c, version), s)
    }

    val (newWeights, newPartialResult) = UserWeightUpdateMethods.calculateSingleUserWeights(ratings, numFeatures)
    userWeights.put((uid, version), newWeights)
  }

  final def updateUsers(uids: TraversableOnce[UserID], version: Version): Unit = {
    uids.foreach(uid => updateUser(uid, version))
  }

  final def writeUserWeights(weights: Map[UserID, WeightVector], version: Version): Unit = {
    weights.foreach(x => userWeights.put((x._1, version), x._2))
  }
}

