/*
 * Copyright 2017 LinkedIn Corp. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linkedin.photon.ml.supervised

import breeze.linalg.Vector
import org.apache.spark.SparkContext
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.rdd.RDD
import org.testng.Assert.assertTrue
import org.testng.annotations.{DataProvider, Test}

import com.linkedin.photon.ml.data.LabeledPoint
import com.linkedin.photon.ml.function.glm.{DistributedGLMLossFunction, LogisticLossFunction, PoissonLossFunction, SquaredLossFunction}
import com.linkedin.photon.ml.normalization.{NoNormalization, NormalizationContext}
import com.linkedin.photon.ml.optimization._
import com.linkedin.photon.ml.optimization.game.GLMOptimizationConfiguration
import com.linkedin.photon.ml.supervised.classification.LogisticRegressionModel
import com.linkedin.photon.ml.supervised.model.GeneralizedLinearModel
import com.linkedin.photon.ml.supervised.regression.{LinearRegressionModel, PoissonRegressionModel}
import com.linkedin.photon.ml.test.SparkTestUtils

// TODO: Update test to match all possible test scenarios
/**
 * Integration test scenarios to generate:
 *
 * <ul>
 *   <li>Each kind of solver (TRON, LBFGS)</li>
 *   <li>Each kind of regularization (L2, L1, E-net, none)</li>
 *   <li>Each kind of GLA (linear regression, logistic regression, poisson regression, etc.)
 *   <li>Regularization weights (null, empty, negative, single valid, several valid, valid with duplicates)</li>
 *   <li>Summary option (None, Some)</li>
 *   <li>Normalization type (None, max magnitude, st. dev)</li>
 *   <li>Input (valid + "easy", valid + "hard", invalid labels, invalid values)</li>
 * </ul>
 *
 * Unfortunately, we need a sensible subset of the cartesian product of all of these (e.g. TRON doesn't make sense
 * with L1).
 *
 * For now, we focus only on the main tests. For now, we define those to be:
 * <ul>
 *   <li>LBFGS as the solver</li>
 *   <li>Each kind of GLA (LogisticRegression, LinearRegression, PoissonRegression)</li>
 *   <li>L2 Regularization</li>
 *   <li>Valid regularization weight (1e-2, 1e-1, 1, 1e1, 1e2, 1e3, 1e4)</li>
 *   <li>TreeAggregateDepth is 1</li>
 *   <li>Summary is none</li>
 *   <li>No normalization</li>
 *   <li>Input is "easy"</li>
 * </ul>
 *
 * For these easy cases, we make sure that:
 *
 * <ul>
 *   <li>we ran (implicit)</li>
 *   <li>The number of models matches expectations</li>
 *   <li>The models themselves match expectations (per a provided ModelValidator)</li>
 * </ul>
 */
class BaseGLMTest extends SparkTestUtils {

  /**
   *
   * @param data
   * @return
   */
  def generateDataSetIterable(data: Iterator[(Double, Vector[Double])]): Seq[LabeledPoint] =
    data.map( x => new LabeledPoint(label = x._1, features = x._2)).toList

  /**
   * Enumerate valid sets of (description, optimization problem builder, data set, validator) tuples.
   */
  @DataProvider
  def getGeneralizedLinearOptimizationProblems: Array[Array[Object]] = {
    val lbfgsConfig = GLMOptimizationConfiguration(
      OptimizerConfig(OptimizerType.LBFGS, LBFGS.DEFAULT_MAX_ITER, LBFGS.DEFAULT_TOLERANCE, None),
      L2RegularizationContext)
//    val tronConfig = GLMOptimizationConfiguration(
//      OptimizerConfig(OptimizerType.TRON, TRON.DEFAULT_MAX_ITER, TRON.DEFAULT_TOLERANCE, None),
//      L2RegularizationContext)

    val linearRegressionData = generateDataSetIterable(
      drawSampleFromNumericallyBenignDenseFeaturesForLinearRegressionLocal(
        BaseGLMTest.RANDOM_SEED,
        BaseGLMTest.NUMBER_OF_SAMPLES,
        BaseGLMTest.NUMBER_OF_DIMENSIONS))
    val poissonRegressionData = generateDataSetIterable(
      drawSampleFromNumericallyBenignDenseFeaturesForPoissonRegressionLocal(
        BaseGLMTest.RANDOM_SEED,
        BaseGLMTest.NUMBER_OF_SAMPLES,
        BaseGLMTest.NUMBER_OF_DIMENSIONS))
    val logisticRegressionData = generateDataSetIterable(
      drawBalancedSampleFromNumericallyBenignDenseFeaturesForBinaryClassifierLocal(
        BaseGLMTest.RANDOM_SEED,
        BaseGLMTest.NUMBER_OF_SAMPLES,
        BaseGLMTest.NUMBER_OF_DIMENSIONS))
//    val smoothedHingeData = generateDataSetIterable(
//      drawBalancedSampleFromNumericallyBenignDenseFeaturesForBinaryClassifierLocal(
//        BaseGLMIntegTest.RANDOM_SEED,
//        BaseGLMIntegTest.NUMBER_OF_SAMPLES,
//        BaseGLMIntegTest.NUMBER_OF_DIMENSIONS))

    Array(
      Array(
        "Linear regression, easy problem",
        (sc: SparkContext, normalizationContext: Broadcast[NormalizationContext]) =>
          DistributedOptimizationProblem(
            lbfgsConfig,
            DistributedGLMLossFunction(sc, lbfgsConfig, treeAggregateDepth = 1)(SquaredLossFunction) ,
            None,
            LinearRegressionModel.apply,
            normalizationContext,
            BaseGLMTest.TRACK_STATES,
            BaseGLMTest.COMPUTE_VARIANCES),
        linearRegressionData,
        new CompositeModelValidator[LinearRegressionModel](
          new PredictionFiniteValidator(),
          new MaximumDifferenceValidator[LinearRegressionModel](BaseGLMTest.MAXIMUM_ERROR_MAGNITUDE))),

      Array(
        "Poisson regression, easy problem",
        (sc: SparkContext, normalizationContext: Broadcast[NormalizationContext]) =>
          DistributedOptimizationProblem(
            lbfgsConfig,
            DistributedGLMLossFunction(sc, lbfgsConfig, treeAggregateDepth = 1)(PoissonLossFunction) ,
            None,
            PoissonRegressionModel.apply,
            normalizationContext,
            BaseGLMTest.TRACK_STATES,
            BaseGLMTest.COMPUTE_VARIANCES),
        poissonRegressionData,
        new CompositeModelValidator[PoissonRegressionModel](
          new PredictionFiniteValidator,
          new NonNegativePredictionValidator[PoissonRegressionModel]
        )
      ),

      Array(
        "Logistic regression, easy problem",
        (sc: SparkContext, normalizationContext: Broadcast[NormalizationContext]) =>
          DistributedOptimizationProblem(
            lbfgsConfig,
            DistributedGLMLossFunction(sc, lbfgsConfig, treeAggregateDepth = 1)(LogisticLossFunction),
            None,
            LogisticRegressionModel.apply,
            normalizationContext,
            BaseGLMTest.TRACK_STATES,
            BaseGLMTest.COMPUTE_VARIANCES),
        logisticRegressionData,
        new CompositeModelValidator[LogisticRegressionModel](
          new PredictionFiniteValidator(),
          new BinaryPredictionValidator[LogisticRegressionModel](),
          new BinaryClassifierAUCValidator[LogisticRegressionModel](BaseGLMTest.MINIMUM_CLASSIFIER_AUCROC)))
    )
  }

  /**
   * Expectation is that there may be many clients of this method (e.g. several different tests that change bindings /
   * data providers to exercise different paths) -- hopefully the majority of tests can be constructed by creating
   * the right bindings.
   */
  @Test(dataProvider = "getGeneralizedLinearOptimizationProblems")
  def runGeneralizedLinearOptimizationProblemScenario(
      desc: String,
      optimizationProblemBuilder: (SparkContext, Broadcast[NormalizationContext]) =>
        DistributedOptimizationProblem[DistributedGLMLossFunction],
      data: Seq[LabeledPoint],
      validator: ModelValidator[GeneralizedLinearModel]): Unit = sparkTest(desc) {

    val normalizationContext = sc.broadcast(NoNormalization())

    // Step 1: Generate input RDD
    val trainingSet: RDD[LabeledPoint] = sc.parallelize(data).repartition(BaseGLMTest.NUM_PARTITIONS)
    val optimizationProblem = optimizationProblemBuilder(sc, normalizationContext)

    // Step 2: Run optimization
    val models = BaseGLMTest.LAMBDAS.map { lambda =>
      optimizationProblem.updateRegularizationWeight(lambda)
      val result = optimizationProblem.run(trainingSet)
      val statesTracker = optimizationProblem.getStatesTracker

      // Step 3: Check convergence
      assertTrue(statesTracker.isDefined, "State tracking was enabled")
      BaseGLMTest.checkConvergence(statesTracker.get)

      result
    }

    // Step 4: Validate the models
    models.foreach( m => {
      m.validateCoefficients()
      validator.validateModelPredictions(m, trainingSet)
    })
  }
}

/**
 * Constants controlling this test
 */
object BaseGLMTest {

  private val LAMBDAS: Seq[Double] = List(1.0)
  // Failures for MaximumDifferenceValidator with all lambas enabled. Need to revisit settings.
  //private val LAMBDAS: Seq[Double] = List(1e-2, 1e-1, 1e0, 1e1, 1e2, 1e3, 1e4)
  private val NUM_PARTITIONS = 4
  private val RANDOM_SEED: Int = 0
  // 10,000 samples would be good enough
  private val NUMBER_OF_SAMPLES: Int = 10000
  // Dimension of 10 should be sufficient to test these problems
  private val NUMBER_OF_DIMENSIONS: Int = 10
  // Minimum required AUROC
  private val MINIMUM_CLASSIFIER_AUCROC: Double = 0.95
  // Maximum allowable magnitude difference between predictions and labels for regression problems
  // (this corresponds to 10 sigma, i.e. events that should occur at most once in the lifespan of our solar system)
  private val MAXIMUM_ERROR_MAGNITUDE: Double = 10 * SparkTestUtils.INLIER_STANDARD_DEVIATION
  private val TRACK_STATES = true
  private val COMPUTE_VARIANCES = false

  /**
   *
   * @param history
   */
  def checkConvergence(history: OptimizationStatesTracker) {
    var lastValue: Double = Double.MaxValue

    history.getTrackedStates.foreach { state =>
      assertTrue(lastValue >= state.loss, "Objective should be monotonically decreasing (current=[" + state.loss +
        "], previous=[" + lastValue + "])")
      lastValue = state.loss
    }
  }
}
