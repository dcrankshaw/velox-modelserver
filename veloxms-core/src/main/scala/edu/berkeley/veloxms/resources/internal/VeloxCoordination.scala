package edu.berkeley.veloxms.resources.internal

import javax.servlet.http.{HttpServlet, HttpServletRequest, HttpServletResponse}

import com.codahale.metrics.Timer
import edu.berkeley.veloxms._
import edu.berkeley.veloxms.background.OnlineUpdateManager
import edu.berkeley.veloxms.models.Model
import edu.berkeley.veloxms.util.{Logging, Utils}
import org.apache.spark.SparkContext

import scala.reflect.ClassTag

case class HDFSLocation(loc: String)
case class LoadModelParameters(userWeightsLoc: String, version: Version)

class DisableOnlineUpdates(
    onlineUpdateManager: OnlineUpdateManager[_],
    timer: Timer) extends HttpServlet with Logging {

  override def doPost(req: HttpServletRequest, resp: HttpServletResponse) {
    val timeContext = timer.time()
    try {
      onlineUpdateManager.disableOnlineUpdates()
      resp.setContentType("application/json")
      jsonMapper.writeValue(resp.getOutputStream, "success")
    } finally {
      timeContext.stop()
    }
  }
}

class EnableOnlineUpdates(
    onlineUpdateManager: OnlineUpdateManager[_],
    timer: Timer) extends HttpServlet with Logging {

  override def doPost(req: HttpServletRequest, resp: HttpServletResponse) {
    val timeContext = timer.time()
    try {
      onlineUpdateManager.enableOnlineUpdates()
      resp.setContentType("application/json")
      jsonMapper.writeValue(resp.getOutputStream, "success")
    } finally {
      timeContext.stop()
    }
  }
}

class WriteTrainingDataServlet[T](
    model: Model[T],
    timer: Timer,
    sparkContext: SparkContext,
    sparkDataLocation: String,
    partition: Int) extends HttpServlet with Logging {

  override def doPost(req: HttpServletRequest, resp: HttpServletResponse) {
    val timeContext = timer.time()
    try {
      val obsLocation = jsonMapper.readValue(req.getInputStream, classOf[HDFSLocation])
      val uri = s"${obsLocation.loc}/part_$partition"

      val observations = model.getObservationsAsRDD(sparkContext)
      observations.saveAsObjectFile(uri)

      resp.setContentType("application/json")
      jsonMapper.writeValue(resp.getOutputStream, "success")
    } finally {
      timeContext.stop()
    }
  }
}


class LoadNewModelServlet(model: Model[_], timer: Timer, sparkContext: SparkContext, sparkDataLocation: String)
    extends HttpServlet with Logging {

  override def doPost(req: HttpServletRequest, resp: HttpServletResponse) {
    val timeContext = timer.time()
    val modelLocation = jsonMapper.readValue(req.getInputStream, classOf[LoadModelParameters])
    try {
      val uri = s"$sparkDataLocation/${modelLocation.userWeightsLoc}"

      // TODO only add users in this partition: if (userId % partNum == 0)
      val users = sparkContext.textFile(s"$uri/users/*").map(line => {
        val userSplits = line.split(", ")
        val userId = userSplits(0).toLong
        val userFeatures: Array[Double] = userSplits.drop(1).map(_.toDouble)
        (userId, userFeatures)
      }).collect().toMap

      val avgUser = sparkContext.textFile(s"$uri/avg_user/*")..map(line => {
        val userSplits = line.split(", ")
        val userFeatures: Array[Double] = userSplits.map(_.toDouble)
        userFeatures
      }).collect().head


      if (users.size > 0) {
        val firstUser = users.head
        logInfo(s"Loaded new models for ${users.size} users. " +
          s"First one is:\n${firstUser._1}, ${firstUser._2.mkString(", ")}")
      }


      // TODO: Should make sure it's sufficiently atomic
      model.averageUser = avgUser
      model.writeUserWeights(users, modelLocation.version)
      model.useVersion(modelLocation.version)

      resp.setContentType("application/json");
      jsonMapper.writeValue(resp.getOutputStream, "success")

    } finally {

      timeContext.stop()
    }
  }
}

class DownloadBulkObservationsServlet[T : ClassTag](
    onlineUpdateManager: OnlineUpdateManager[T],
    timer: Timer,
    modelName: String,
    partitionMap: Seq[String],
    hostname: String,
    sparkContext: SparkContext) extends HttpServlet with Logging {

  override def doPost(req: HttpServletRequest, resp: HttpServletResponse) {
    val timeContext = timer.time()
    try {
      val obsLocation = jsonMapper.readValue(req.getInputStream, classOf[HDFSLocation])

      val thisPartition = partitionMap.indexOf(hostname)
      val numPartitions = partitionMap.size
      val observations = sparkContext.objectFile[(UserID, T, Double)](obsLocation.loc).filter { x =>
        val uid = x._1
        Utils.nonNegativeMod(uid.hashCode(), numPartitions) == thisPartition
      }

      observations.collect().foreach { case (uid, item, score) =>
        onlineUpdateManager.addObservation(uid, item, score)
      }

      resp.setContentType("application/json")
      jsonMapper.writeValue(resp.getOutputStream, "success")
    } finally {
      timeContext.stop()
    }
  }
}


