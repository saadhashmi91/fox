/*
 *  @author Philip Stutz
 *  @author Sara Magliacane
 *
 *  Copyright 2014 University of Zurich & VU University Amsterdam
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package com.signalcollect.psl

import com.signalcollect.ExecutionInformation
import com.signalcollect.admm.Wolf
import com.signalcollect.admm.WolfConfig
import com.signalcollect.admm.ProblemSolution
import com.signalcollect.admm.utils.Timer
import com.signalcollect.admm.optimizers.OptimizableFunction
import com.signalcollect.psl.parser.ParsedPslFile
import com.signalcollect.psl.parser.PslParser
import com.signalcollect.psl.parser.Fact
import com.signalcollect.psl.model.Predicate
import com.signalcollect.psl.model.PredicateInRule
import com.signalcollect.psl.model.Rule
import com.signalcollect.psl.model.GroundedPredicate
import com.signalcollect.psl.model.GroundedRule
import com.signalcollect.psl.model.GroundedConstraint
import java.io.File
import akka.actor.ActorRef
import com.signalcollect.psl.model.GroundedPredicate

/**
 * The main class for PSL inference.
 * Takes in a file/string/parsed PSL file, [parses it], grounds it, converts it to ADMM and calls Wolf, the S/C ADMM solver.
 * Returns the inferences of the unknown grounded predicates.
 */

case class InferenceResult(
  solution: ProblemSolution,
  idToGpMap: Map[Int, GroundedPredicate],
  objectiveFun: Option[Double] = None,
  groundingTime: Option[Long] = None,
  parsingTime: Option[Long] = None,
  functionCreationTime: Option[Long] = None,
  numGroundedRules: Option[Int] = None,
  numGroundedConstraints: Option[Int] = None,
  numBoundedVars: Option[Int] = None,
  numFunctions: Option[Int] = None,
  numConstraints: Option[Int] = None) {

  def getGp(predicate: String, individuals: String*): Option[GroundedPredicate] = {
    val individualList = individuals.toList
    val foundOption = idToGpMap.find {
      case (id, gp) =>
        gp.definition.name == predicate &&
          gp.groundings.map(_.name) == individualList
    }
    foundOption.map(_._2)
  }

  def truthValue(predicate: String, individuals: String*): Option[Double] = {
    val gpOption = getGp(predicate, individuals: _*)
    val truthValue = gpOption.map(gp => solution.results(gp.id))
    truthValue
  }

  override def toString() = {
    var s = solution.stats.toString
    s += printSelectedResults(printFacts = true)
    objectiveFun match {
      case Some(x) =>
        s += s"\nObjective function value: $x"
      case None =>
    }
    groundingTime match {
      case Some(x) =>
        s += s"\nGrounding time: $groundingTime"
      case None =>
    }
    s
  }

  def nicerTruthValue(t: Double): Double = {
    def goodEnough(v: Double): Boolean = math.abs(v - t) <= 1e-3
    lazy val fraction = 1.0 / (1.0 / t).round
    lazy val multipleTenths = (t * 10.0).round / 10.0
    lazy val multipleHundreds = (t * 100.0).round / 100.0
    if (goodEnough(0.0)) {
      0.0
    } else if (goodEnough(fraction)) {
      fraction
    } else if (goodEnough(multipleTenths)) {
      multipleTenths
    } else if (goodEnough(multipleHundreds)) {
      multipleHundreds
    } else {
      t
    }
  }

  def getSelectedResultsOnly(predicateNames: List[String] = List.empty, printOutZeros: Boolean = false) = {
    solution.results.toScalaMap.flatMap {
      case (id, truthValue) =>
        if (printOutZeros || truthValue > 0) {
          val gp = idToGpMap(id)
          if (predicateNames.isEmpty || predicateNames.contains(gp.definition.name)) {
            Some(Map(gp -> truthValue))
          } else { None }
        } else { None }
    }.flatten.toList
  }

  def getSelectedResultsAndFacts(predicateNames: List[String] = List.empty, printOutZeros: Boolean = false) = {
    idToGpMap.flatMap {
      case (id, gp) =>
        if (predicateNames.isEmpty || predicateNames.contains(gp.definition.name)) {
          val truthValue = gp.truthValue.getOrElse(solution.results.get(id))
          if (printOutZeros || truthValue > 0) {
            Some(Map(gp -> truthValue))
          } else { None }
        } else { None }
    }.flatten.toList
  }

  def getSortedSelectedResults(predicateNames: List[String] = List.empty, printFacts: Boolean = true, sortById: Boolean = false, printOutZeros: Boolean = false) = {
    val listGpToTruthValue = if (printFacts) {
      getSelectedResultsAndFacts(predicateNames, printOutZeros)
    } else {
      getSelectedResultsOnly(predicateNames, printOutZeros)
    }
    if (!sortById) {
      listGpToTruthValue.sortBy(
        f =>
          // (predicate name, groundings in alphabetical order)
          (f._1.definition.name, f._1.groundings.toString))
    } else {
      listGpToTruthValue.sortBy(f => f._1.id)
    }
  }

  def printSelectedResults(predicateNames: List[String] = List.empty, printFacts: Boolean = true, sortById: Boolean = false, printOutZeros: Boolean = false,
    outputType: String = "inference") = {
    var s = ""
    val sortedListGpToTruthValue = getSortedSelectedResults(predicateNames, printFacts, sortById, printOutZeros)
    sortedListGpToTruthValue.foreach {
      case (gp, truthValue) =>
        if (outputType == "shortInference") {
          s += s"\n$gp -> ${nicerTruthValue(truthValue)}"
        } else if (outputType == "onlyTrueFacts" && truthValue > 0.5) {
          s += s"""${gp.definition.name}${gp.groundings.mkString("(", ",", ")")} """
        } else if (outputType == "inference") {
          s += "\n" + s"""${gp.definition.name}${gp.groundings.mkString("(", ",", ")")}= ${nicerTruthValue(truthValue)}"""
        } else {
          // Do nothing.
        }
    }
    s
  }
}

case class InferencerConfig(
  asynchronous: Boolean = false,
  lazyThreshold: Option[Double] = Some(1e-13), // Absolute threshold for lazy vertices.
  breezeOptimizer: Boolean = true,
  globalConvergenceDetection: Option[Int] = Some(100), // Run convergence detection every 100 S/C steps.
  absoluteEpsilon: Double = 1e-8,
  relativeEpsilon: Double = 1e-3,
  computeObjectiveValueOfSolution: Boolean = false,
  objectiveLoggingEnabled: Boolean = false,
  maxIterations: Int = 10000, // maximum number of iterations.
  timeLimit: Option[Long] = None,
  stepSize: Double = 1.0,
  tolerance: Double = 1e-12, // for Double precision is 15-17 decimal places, lower after arithmetic operations.
  isBounded: Boolean = true,
  parallelizeParsing: Boolean = true,
  removeSymmetricConstraints: Boolean = true,
  parallelizeGrounding: Boolean = true,
  pushBoundsInNodes: Boolean = true,
  optimizedFunctionCreation: Boolean = true,
  serializeMessages: Boolean = false,
  eagerSignalCollectConvergenceDetection: Boolean = true,
  heartbeatIntervalInMs: Int = 0,
  verbose: Boolean = false) {

  override def toString: String =
    s"asynchronous: $asynchronous, lazyThreshold: $lazyThreshold, breezeOptimizer: $breezeOptimizer, globalConvergenceDetection: $globalConvergenceDetection, absoluteEpsilon: $absoluteEpsilon, relativeEpsilon: $relativeEpsilon, computeObjectiveValueOfSolution: $computeObjectiveValueOfSolution, objectiveLoggingEnabled: $objectiveLoggingEnabled, maxIterations: $maxIterations, stepSize: $stepSize, tolerance: $tolerance, isBounded: $isBounded, removeSymmetricConstraints: $removeSymmetricConstraints, parallelizeGrounding: $parallelizeGrounding, pushBoundsInNodes: $pushBoundsInNodes, optimizedFunctionCreation: $optimizedFunctionCreation, verbose: $verbose"

  def getWolfConfig = {
    WolfConfig(
      asynchronous = asynchronous,
      lazyThreshold = lazyThreshold,
      globalConvergenceDetection = globalConvergenceDetection,
      objectiveLoggingEnabled = objectiveLoggingEnabled,
      absoluteEpsilon = absoluteEpsilon,
      relativeEpsilon = relativeEpsilon,
      maxIterations = maxIterations,
      timeLimit = timeLimit,
      stepSize = stepSize,
      isBounded = isBounded,
      serializeMessages = serializeMessages,
      eagerSignalCollectConvergenceDetection = eagerSignalCollectConvergenceDetection,
      heartbeatIntervalInMs = heartbeatIntervalInMs)
  }
}

object Inferencer {

  /**
   *  Utility method that takes care also of the parsing from several files.
   */
  def runInferenceFromFiles(
    pslFiles: List[File],
    nodeActors: Option[Array[ActorRef]] = None,
    config: InferencerConfig = InferencerConfig()): InferenceResult = {
    val (pslData, parsingTime) = Timer.time {
      if (config.parallelizeParsing) {
        PslParser.parse(pslFiles)
      } else {
        PslParser.parseNonParallel(pslFiles)
      }
    }
    runInference(pslData, parsingTime, nodeActors, config)
  }

  /**
   *  Utility method that takes care also of the parsing of a file.
   */
  def runInferenceFromFile(
    pslFile: File,
    nodeActors: Option[Array[ActorRef]] = None,
    config: InferencerConfig = InferencerConfig()): InferenceResult = {
    val (pslData, parsingTime) = Timer.time {
      if (config.parallelizeParsing) {
        PslParser.parseFileLineByLine(pslFile).toParsedPslFile()
      } else {
        PslParser.parse(pslFile)
      }
    }
    runInference(pslData, parsingTime, nodeActors, config)
  }

  /**
   *  Utility method that takes care also of the parsing of a string.
   */
  def runInferenceFromString(
    pslFile: String,
    nodeActors: Option[Array[ActorRef]] = None,
    config: InferencerConfig = InferencerConfig()): InferenceResult = {
    val (pslData, parsingTime) = Timer.time { PslParser.parse(pslFile) }
    runInference(pslData, parsingTime, nodeActors, config)
  }

  /**
   * Runs inference on the passed PSL file by using ADMM for consensus optimization.
   * Returns an InferenceResults, containing the ProblemSolution from Wolf, the mapping from predicates to ids
   * and optionally the value of the objective function.
   */
  def runInference(
    pslData: ParsedPslFile,
    parsingTime: Long,
    nodeActors: Option[Array[ActorRef]] = None,
    config: InferencerConfig = InferencerConfig()): InferenceResult = {
    // Ground the rules with the individuals.
    val ((groundedRules, groundedConstraints, idToGpMap), groundingTime) = Timer.time {
      var individualsString = s"Running inferences for ${pslData.individuals.size} individuals ..."
      if (pslData.individuals.size <= 5) {
        individualsString += pslData.individuals.map(i => s"${i.value}: ${i.classTypes}")
      } else {
        individualsString += "first 5 results: "
        individualsString += pslData.individuals.slice(0, 5).map(i => s"${i.value}: ${i.classTypes}")
      }
      println(individualsString)
      Grounding.ground(pslData, config)
    }
    // groundedRules.map(println(_))
    // groundedConstraints.map(println(_))
    println(s"Grounding completed in $groundingTime ms: ${groundedRules.size} grounded rules, ${groundedConstraints.size} constraints and ${idToGpMap.keys.size} grounded predicates.")
    solveInferenceProblem(groundedRules, groundedConstraints, idToGpMap, groundingTime, parsingTime, nodeActors, config)
  }

  def recreateFunctions(groundedRules: Iterable[GroundedRule], groundedConstraints: Iterable[GroundedConstraint], idToGpMap: Map[Int, GroundedPredicate], config: InferencerConfig = InferencerConfig()): (Iterable[OptimizableFunction], Iterable[OptimizableFunction], Map[Int, (Double, Double)]) = {
    val functions = groundedRules.flatMap(_.createOptimizableFunction(config.stepSize, config.tolerance, config.breezeOptimizer, config.optimizedFunctionCreation))
    val constraints = groundedConstraints.flatMap(_.createOptimizableFunction(config.stepSize, config.tolerance, config.breezeOptimizer, config.optimizedFunctionCreation))
    val boundsForConsensusVariables: Map[Int, (Double, Double)] = if (config.pushBoundsInNodes && config.isBounded) {
      idToGpMap.filter(p => p._2.lowerBound != 0.0 || p._2.upperBound != 1.0).map {
        case (id, p) => (id, (p.lowerBound, p.upperBound))
      }
    } else {
      Map.empty
    }
    println(s"Problem converted to consensus optimization with ${functions.size} functions,  ${constraints.size} constraints and ${boundsForConsensusVariables.size} bounds.")
    (functions, constraints, boundsForConsensusVariables)
  }

  def solveInferenceProblem(groundedRules: Iterable[GroundedRule], groundedConstraints: Iterable[GroundedConstraint], idToGpMap: Map[Int, GroundedPredicate],
    groundingTime: Long, parsingTime: Long,
    nodeActors: Option[Array[ActorRef]] = None, config: InferencerConfig = InferencerConfig()) = {
    val ((functions, constraints, boundsForConsensusVariables), functionCreationTime) = Timer.time {
      recreateFunctions(groundedRules, groundedConstraints, idToGpMap, config)
    }

    val solution = Wolf.solveProblem(
      functions ++ constraints,
      nodeActors,
      config.getWolfConfig,
      boundsForConsensusVariables)

    val (objectiveFunctionVal: Option[Double], objEvaluationTime) = Timer.time {
      if (config.computeObjectiveValueOfSolution) {
        val result = (functions ++ constraints).foldLeft(0.0) {
          case (sum, nextFunction) => sum + nextFunction.evaluateAt(solution.results)
        }
        Some(result)
      } else { None }
    }
    //println("Computed the objective function.")
    InferenceResult(solution, idToGpMap, objectiveFunctionVal,
      groundingTime = Some(groundingTime), parsingTime = Some(parsingTime), functionCreationTime = Some(functionCreationTime + objEvaluationTime),
      numGroundedRules = Some(groundedRules.size),
      numGroundedConstraints = Some(groundedConstraints.size),
      numBoundedVars = Some(boundsForConsensusVariables.size),
      numFunctions = Some(functions.size),
      numConstraints = Some(constraints.size))
  }
}
