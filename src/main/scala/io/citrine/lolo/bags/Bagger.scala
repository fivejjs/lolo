package io.citrine.lolo.bags

import breeze.linalg.DenseMatrix
import breeze.stats.distributions.{Poisson, Rand, RandBasis}
import io.citrine.lolo.stats.metrics.ClassificationMetrics
import io.citrine.lolo.util.{Async, InterruptibleExecutionContext}
import io.citrine.lolo.{Learner, Model, PredictionResult, RegressionResult, TrainingResult}

import scala.collection.parallel.ExecutionContextTaskSupport
import scala.collection.parallel.immutable.ParSeq
import scala.reflect._

/**
  * A bagger creates an ensemble of models by training the learner on random samples of the training data
  *
  * Created by maxhutch on 11/14/16.
  *
  * @param method  learner to train each model in the ensemble
  * @param numBags number of base models to aggregate (default of -1 sets the number of models to the number of training rows)
  * @param useJackknife whether to enable jackknife uncertainty estimate
  * @param biasLearner learner to use for estimating bias
  * @param uncertaintyCalibration whether to enable empirical uncertainty calibration
  * @param disableBootstrap whether to disable bootstrap (useful when `method` implements its own randomization)
  * @param randBasis breeze RandBasis to use for generating breeze random numbers
  * */
case class Bagger(
                   method: Learner,
                   numBags: Int = -1,
                   useJackknife: Boolean = true,
                   biasLearner: Option[Learner] = None,
                   uncertaintyCalibration: Boolean = false,
                   disableBootstrap: Boolean = false,
                   randBasis: RandBasis = Rand
                 ) extends Learner {
  require(
    !(uncertaintyCalibration && disableBootstrap),
    "Options uncertaintyCalibration and disableBootstrap are incompatible. At most one may be set true.")

  /**
    * Draw with replacement from the training data for each model
    *
    * @param trainingData to train on
    * @param weights      for the training rows, if applicable
    * @return a model
    */
  override def train(trainingData: Seq[(Vector[Any], Any)], weights: Option[Seq[Double]] = None): BaggedTrainingResult[Any] = {
    /* Make sure the training data is the same size */
    assert(trainingData.forall(trainingData.head._1.size == _._1.size))
    require(
      trainingData.size >= Bagger.minimumTrainingSize,
      s"We need to have at least ${Bagger.minimumTrainingSize} rows, only ${trainingData.size} given"
    )

    val isRegression: Boolean = trainingData.head._2 match {
      case _: Double => true
      case _: Any => false
    }

    /* Use unit weights if none are specified */
    val weightsActual = weights.getOrElse(Seq.fill(trainingData.size)(1.0))

    /* Set default number of bags */
    val actualBags = if (numBags > 0) {
      numBags
    } else {
      trainingData.size
    }

    // We need enough bags such that the probability that the poisson draw is "valid" is at least 50%
    // Valid here means that for each training point, there is at least one tree that doesn't include it
    // The probability that the weights are valid is:
    // (1 - [(1 - 1/e)^{number of trees}])^{number of training points}
    val minBags = Math.log(1 - Math.pow(2, -1.0 / trainingData.size)) / Math.log((Math.E - 1) / Math.E)
    require(
      !useJackknife || actualBags >= minBags,
      s"Jackknife requires ${minBags} bags for ${trainingData.size} training rows, but only ${actualBags} given."
    )

    /* Compute the number of instances of each training row in each training sample */
    val dist = new Poisson(1.0)(randBasis)
    val Nib: Vector[Vector[Int]] = if (disableBootstrap) {
      Vector.fill[Vector[Int]](actualBags)(Vector.fill[Int](trainingData.size)(1))
    } else {
      Iterator.continually{
        // Generate Poisson distributed weights, filtering out any that don't have the minimum required number
        // of non-zero training weights
        Iterator.continually {
          Vector.fill(trainingData.size)(dist.draw())
        }.filter(_.count(_ > 0) >= Bagger.minimumNonzeroWeightSize).take(actualBags).toVector
      }.filter{nMat =>
        lazy val noAlwaysPresentTrainingData = nMat.transpose.forall{vec => vec.contains(0)}
        // Make sure that at least one learner is missing each training point
        // This prevents a divide-by-zero error in the jackknife-after-bootstrap calculation
        !useJackknife || noAlwaysPresentTrainingData
      }.next()
    }

    /* Learn the actual models in parallel */
    val parIterator = (0 until actualBags).par
    parIterator.tasksupport = new ExecutionContextTaskSupport(InterruptibleExecutionContext())
    val (models, importances: ParSeq[Option[Vector[Double]]]) = parIterator.map { i =>
      // get weights
      val sampleWeights = Nib(i).zip(weightsActual).map(p => p._1.toDouble * p._2)

      // Train the model
      val meta = method.train(trainingData.toVector, Some(sampleWeights))

      // Extract the model and feature importance from the TrainingResult
      (meta.getModel(), meta.getFeatureImportance())
    }.unzip

    // Average the feature importances
    val averageImportance: Option[Vector[Double]] = importances
      .reduce(Bagger.combineImportance)
      .map(_.map(_ / importances.size))

    /* Out-of-bag error and uncertainty for each point, calculating by combining the result of each tree.
    Define as lazy so we only compute them if they are needed for the ratio or bias learner calculation */
    lazy val oobErrors: Seq[(Vector[Any], Double, Double)] = trainingData.indices.flatMap { idx =>
      val oobModels = models.zip(Nib.map(_ (idx))).filter(_._2 == 0).map(_._1).asInstanceOf[ParSeq[Model[PredictionResult[Double]]]]
      if (oobModels.size < 2) {
        None
      } else {
        Async.canStop()
        val model = new BaggedModel(oobModels, Nib.filter {
          _ (idx) == 0
        }, useJackknife, disableBootstrap = disableBootstrap)
        val predicted = model.transform(Seq(trainingData(idx)._1))
        val error = predicted.getExpected().head - trainingData(idx)._2.asInstanceOf[Double]
        val uncertainty = predicted match {
          case x: RegressionResult => x.getStdDevObs.get.head
          case _: Any => throw new UnsupportedOperationException("Computing oobErrors for classification is not supported.")
        }
        Some(trainingData(idx)._1, error, uncertainty)
      }
    }

    /* Calculate the uncertainty calibration ratio, which is the 68th percentile of error/uncertainty
    for the training points. If a point has 0 uncertainty, the ratio is 1 if error is also 0, otherwise infinity */
    val ratio = if (uncertaintyCalibration && isRegression && useJackknife) {
      Async.canStop()
      oobErrors.map {
        case (_, 0.0, 0.0) => 1.0
        case (_, _, 0.0) => Double.PositiveInfinity
        case (_, error, uncertainty) => Math.abs(error / uncertainty)
      }.sorted.drop((oobErrors.size * 0.68).toInt).head
    } else {
      1.0
    }
    assert(!ratio.isNaN && !ratio.isInfinity, s"Uncertainty calibration ratio is not real: $ratio")

    /* Wrap the models in a BaggedModel object */
    if (biasLearner.isEmpty || oobErrors.isEmpty) {
      Async.canStop()
      if (isRegression) {
        new BaggedTrainingResult(models.asInstanceOf[ParSeq[Model[PredictionResult[Double]]]], averageImportance, Nib, trainingData, useJackknife, None, ratio, disableBootstrap)
      } else {
        new BaggedTrainingResult(models, averageImportance, Nib, trainingData, useJackknife, None, ratio, disableBootstrap)
      }
    } else {
      val biasTraining = oobErrors.map { case (f, e, u) =>
        // Math.E is only statistically correct.  It should be actualBags / Nib.transpose(i).count(_ == 0)
        // Or, better yet, filter the bags that don't include the training example
        val bias = Math.max(Math.abs(e) - u * ratio, 0)
        (f, bias)
      }
      Async.canStop()
      val biasModel = biasLearner.get.train(biasTraining).getModel().asInstanceOf[Model[PredictionResult[Double]]]
      Async.canStop()

      if (isRegression) {
        new BaggedTrainingResult[Double](models.asInstanceOf[ParSeq[Model[PredictionResult[Double]]]], averageImportance, Nib, trainingData, useJackknife, Some(biasModel), ratio, disableBootstrap)
      } else {
        new BaggedTrainingResult[Any](models, averageImportance, Nib, trainingData, useJackknife, None, ratio, disableBootstrap)
      }
    }
  }
}

class BaggedTrainingResult[+T : ClassTag](
                            models: ParSeq[Model[PredictionResult[T]]],
                            featureImportance: Option[Vector[Double]],
                            Nib: Vector[Vector[Int]],
                            trainingData: Seq[(Vector[Any], Any)],
                            useJackknife: Boolean,
                            biasModel: Option[Model[PredictionResult[T]]] = None,
                            rescale: Double = 1.0,
                            disableBootstrap: Boolean = false
                          )
  extends TrainingResult {

  lazy val NibT = Nib.transpose
  lazy val model = new BaggedModel[T](models, Nib, useJackknife, biasModel, rescale, disableBootstrap)
  lazy val rep = trainingData.find(_._2 != null).get._2
  lazy val predictedVsActual = trainingData.zip(NibT).flatMap { case ((f, l), nb) =>
    val oob = if (disableBootstrap) {
      models.zip(nb)
    } else {
      models.zip(nb).filter(_._2 == 0)
    }

    if (oob.isEmpty || l == null || (l.isInstanceOf[Double] && l.asInstanceOf[Double].isNaN)) {
      Seq()
    } else {
      val predicted = l match {
        case _: Double => oob.map(_._1.transform(Seq(f)).getExpected().head.asInstanceOf[Double]).sum / oob.size
        case _: Any => oob.map(_._1.transform(Seq(f)).getExpected().head).groupBy(identity).maxBy(_._2.size)._1
      }
      Seq((f, predicted, l))
    }
  }

  lazy val loss: Double = rep match {
    case x: Double => Math.sqrt(predictedVsActual.map(d => Math.pow(d._2.asInstanceOf[Double] - d._3.asInstanceOf[Double], 2)).sum / predictedVsActual.size)
    case x: Any =>
      val f1 = ClassificationMetrics.f1scores(predictedVsActual)
      if (f1 > 0.0) 1.0 / f1 - 1.0 else Double.MaxValue
  }

  /**
    * Average the influences across the ensemble of models
    *
    * @return feature influences as an array of doubles
    */
  override def getFeatureImportance(): Option[Vector[Double]] = featureImportance

  override def getModel(): BaggedModel[Any] = model

  override def getPredictedVsActual(): Option[Seq[(Vector[Any], Any, Any)]] = Some(predictedVsActual)

  override def getLoss(): Option[Double] = {
    if (predictedVsActual.nonEmpty) {
      Some(loss)
    } else {
      None
    }
  }
}

/**
  * Container holding a parallel sequence of models and the sample counts used to train them
  *
  * @param models in this bagged model
  * @param Nib    training sample counts
  */
class BaggedModel[+T: ClassTag](
                   models: ParSeq[Model[PredictionResult[T]]],
                   Nib: Vector[Vector[Int]],
                   useJackknife: Boolean,
                   biasModel: Option[Model[PredictionResult[T]]] = None,
                   rescale: Double = 1.0,
                   disableBootstrap: Boolean = false
                 ) extends Model[BaggedResult[T]] {

  /**
    * Apply each model to the outputs and wrap them up
    *
    * @param inputs to apply the model to
    * @return a predictionresult that includes uncertainties and scores
    */
  override def transform(inputs: Seq[Vector[Any]]): BaggedResult[T] = {
    assert(inputs.forall(_.size == inputs.head.size))
    val isRegression = classTag[T].runtimeClass == classOf[Double]

    val bias = if (biasModel.isDefined) {
      Some(biasModel.get.transform(inputs).getExpected().asInstanceOf[Seq[Double]])
    } else {
      None
    }
    val ensemblePredictions = models.map(model => model.transform(inputs)).seq

    val res = if (inputs.size == 1 && isRegression) {
      // In the special case of a single prediction on a real value, emit an optimized BaggedSingleResult
      BaggedSingleResult(ensemblePredictions.map(_.asInstanceOf[PredictionResult[Double]]), Nib, bias.map(_.head), rescale, disableBootstrap)
    } else if (isRegression) {
      BaggedMultiResult(ensemblePredictions.map(_.asInstanceOf[PredictionResult[Double]]), Nib, bias, rescale, disableBootstrap)
    } else {
      BaggedClassificationResult(ensemblePredictions)
    }

    res.asInstanceOf[BaggedResult[T]]
  }

  /**
    * Compute Shapley feature attributions for a given input
    *
    * @param input for which to compute feature attributions.
    * @param omitFeatures feature indices to omit in computing Shapley values
    * @return matrix of attributions for each feature and output
    *         One row per feature, each of length equal to the output dimension.
    *         The output dimension is 1 for single-task regression, or equal to the number of classification categories.
    */
  override def shapley(input: Vector[Any], omitFeatures: Set[Int] = Set()): Option[DenseMatrix[Double]] = {
    val ensembleShapley = models.map(model => model.shapley(input, omitFeatures))
    if (!ensembleShapley.head.isDefined) {
      None
    }
    assert(ensembleShapley.forall(x => x.isDefined))

    def sumReducer(a: Option[DenseMatrix[Double]],
                   b: Option[DenseMatrix[Double]]): Option[DenseMatrix[Double]] = {
      Some(a.get + b.get)
    }
    val scale = 1.0 / ensembleShapley.length

    Some(scale * ensembleShapley.reduce(sumReducer).get)
  }

  // Accessor useful for testing.
  private[bags] def getModels(): ParSeq[Model[PredictionResult[T]]] = models
}

object Bagger {

  /**
    * The minimum number of training rows in order to train a Bagger
    */
  val minimumTrainingSize: Int = 8

  /**
    * The minimum number of non-zero weighted training points that is based into the learner
    *
    * This requirement biases the poisson draws, but hopefully not too too much
    */
  val minimumNonzeroWeightSize: Int = 4

  private def combineImportance(v1: Option[Vector[Double]], v2: Option[Vector[Double]]): Option[Vector[Double]] = {
    (v1, v2) match {
      case (None, None) => None
      case (Some(v1: Vector[Double]), Some(v2: Vector[Double])) => Some(v1.zip(v2).map(p => p._1 + p._2))
      case _ => None
    }
  }
}
