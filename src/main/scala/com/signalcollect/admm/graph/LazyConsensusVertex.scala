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

package com.signalcollect.admm.graph

import com.signalcollect.GraphEditor
import com.signalcollect.MemoryEfficientDataGraphVertex

/**
 * Lazy version of the consensus vertex, only signals if something has changed.
 */
class LazyConsensusVertex(
  variableId: Int, // the id of the variable, which identifies it also in the subproblem nodes.
  initialState: Double = 0.0, // the initial value for the consensus variable.
  isBounded: Boolean = true // shall we use bounding (cutoff below 0 and above 1)? 
  ) extends ConsensusVertex(variableId, initialState, isBounded) {

  /**
   * Only signal if the state has changed.
   */
  override def scoreSignal = {
    if (state != lastSignalState) {
      1.0
    } else {
      0.0
    }
  }

}