package io.citrine.lolo

import breeze.stats.distributions.{RandBasis, ThreadLocalRandomGenerator}
import io.citrine.lolo.stats.functions.Friedman
import org.apache.commons.math3.random.MersenneTwister

import scala.collection.generic.CanBuildFrom
import scala.collection.mutable
import scala.util.{Random, Try}

/**
  * Created by maxhutch on 11/28/16.
  */
object TestUtils {

  def readCsv(name: String): Seq[Vector[Any]] = {
    val stream = getClass.getClassLoader.getResourceAsStream(name)
    val bs = scala.io.Source.fromInputStream(stream)
    val res = bs.getLines().flatMap{line =>
      Try(line.split(",").map(_.trim).map { token =>
        try {
          token.toDouble
        } catch {
          case _: Throwable if token == "NaN" => Double.NaN
          case _: Throwable if token.nonEmpty => token
        }
      }.toVector).toOption
    }.toVector
    bs.close()
    res
  }

  def generateTrainingData(
                            rows: Int,
                            cols: Int,
                            function: (Seq[Double] => Double) = Friedman.friedmanGrosseSilverman,
                            xscale: Double = 1.0,
                            xoff: Double = 0.0,
                            noise: Double = 0.0,
                            seed: Long = 0L
                          ): Vector[(Vector[Double], Double)] = {
    val rnd = new Random(seed)
    Vector.fill(rows) {
      val input = Vector.fill(cols)(xscale * rnd.nextDouble() + xoff)
      (input, function(input) + noise * rnd.nextGaussian())
    }
  }

  def iterateTrainingData(
                           cols: Int,
                           function: (Seq[Double] => Double) = Friedman.friedmanGrosseSilverman,
                           xscale: Double = 1.0,
                           xoff: Double = 0.0,
                           noise: Double = 0.0,
                           seed: Long = 0L
                          ): Iterator[(Vector[Double], Double)] = {
    val rnd = new Random(seed)
    Iterator.continually {
      val input = Vector.fill(cols)(xscale * rnd.nextDouble() + xoff)
      (input, function(input) + noise * rnd.nextGaussian())
    }
  }

  def binTrainingData(continuousData: Seq[(Vector[Double], Double)],
                      inputBins: Seq[(Int, Int)] = Seq(),
                      responseBins: Option[Int] = None
                     ): Seq[(Vector[Any], Any)] = {
    var outputData: Seq[(Vector[Any], Any)] = continuousData
    inputBins.foreach { case (index, nBins) =>
      outputData = outputData.map { case (input, response) =>
        (input.updated(index, Math.round(input(index).asInstanceOf[Double] * nBins).toString), response)
      }
    }
    responseBins.foreach { nBins =>
      val max = continuousData.map(_._2).max
      val min = continuousData.map(_._2).min
      outputData = outputData.map { case (input, response) =>
        (input, Math.round(response.asInstanceOf[Double] * nBins / (max - min)).toString)
      }
    }
    outputData
  }

  /**
    * Enumerate the cartesian product of items in baseGrids.
    *
    * @param baseGrids a sequence of 1-d mesh specifications, one for each dimension of the output vectors
    * @return a sequence of vectors enumerating the cartesian product of items in baseGrids
    */
  def enumerateGrid(baseGrids: Seq[Seq[Double]]): Seq[Vector[Double]] = {
    if (baseGrids.length == 1) {
      baseGrids.head.map { x => Vector(x) }
    } else {
      baseGrids.head.flatMap { x =>
        enumerateGrid(baseGrids.takeRight(baseGrids.length - 1)).map { n =>
          x +: n
        }
      }
    }
  }

  def getBreezeRandBasis(seed: Long): RandBasis = new RandBasis(new ThreadLocalRandomGenerator(new MersenneTwister(seed)))
}

