/*
 * NOTICE: The original file was changed by Philip Stutz and Sara Magliacane.
 * 
 * This file is part of the PSL software.
 * Copyright 2011-2013 University of Maryland
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.signalcollect.psl

import org.scalatest.FlatSpec
import org.scalatest.Matchers
import com.signalcollect.util.TestAnnouncements

class SquaredLinearLossSpec extends FlatSpec with Matchers with TestAnnouncements {

  "SquaredLinearLoss" should "correctly solve problem A" in {
    val z = Array(0.4, 0.5, 0.1)
    val y = Array(0.0, 0.0, -0.05)
    val coeffs = Array(0.3, -1.0, 0.4)
    val constant = -20.0
    val weight = 0.5
    val stepSize = 2.0
    val expected = Array(-1.41569, 6.55231, -2.29593)
    testProblem(z, y, coeffs, constant, weight, stepSize, expected)
  }

  def testProblem(
    z: Array[Double],
    y: Array[Double],
    coeffs: Array[Double],
    constant: Double,
    weight: Double,
    stepSize: Double,
    expected: Array[Double]) {
    val zMap = (0 until z.length).zip(z).toMap
    val hlt = Optimizer.squaredLinearLoss(
      stepSize,
      zMap,
      weight,
      constant,
      coeffs,
      zMap.keys.toArray,
      1)
    hlt.setY(y)
    val resultMap = hlt.optimize(zMap)
    for (i <- 0 until z.length) {
      resultMap(i) should be(expected(i) +- 5e-5)
    }
  }

}