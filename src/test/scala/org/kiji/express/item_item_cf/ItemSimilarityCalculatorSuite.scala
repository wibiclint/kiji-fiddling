/**
 * (c) Copyright 2013 WibiData, Inc.
 *
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.kiji.express.item_item_cf

import scala.collection.mutable
import scala.math.abs

import cascading.tuple.Fields
import com.twitter.scalding.Args
import com.twitter.scalding.JobTest
import com.twitter.scalding.TextLine
import com.twitter.scalding.Tsv
import org.apache.avro.generic.GenericRecord
import org.apache.avro.generic.GenericRecordBuilder
import org.apache.avro.specific.SpecificRecord
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.kiji.express._
import org.kiji.express.flow._
import org.kiji.express.flow.util.Resources.doAndRelease
import org.kiji.schema.KijiTable
import org.kiji.schema.KijiURI
import org.kiji.schema.layout.KijiTableLayout

// Unsavory hacks for convenient comparison of doubles:
case class Precision(val p:Double)

/*
// Scala 2.10:
implicit class DoubleWithAlmostEquals(val d:Double) extends AnyVal {
    def ~=(d2:Double)(implicit p:Precision) = (d - d2).abs < p.p
}
*/
class WithAlmostEquals(d:Double) {
    def ~=(d2:Double)(implicit p:Precision) = (d-d2).abs <= p.p
}

@RunWith(classOf[JUnitRunner])
class ItemSimilarityCalculatorSuite extends KijiSuite {
  implicit def add_~=(d:Double) = new WithAlmostEquals(d)

  implicit val precision = Precision(0.001)

  val logger: Logger = LoggerFactory.getLogger(classOf[ItemSimilarityCalculatorSuite])

  val avroLayout: KijiTableLayout = layout("user_ratings.json")

  val uri: String = doAndRelease(makeTestKijiTable(avroLayout)) { table: KijiTable =>
    table.getURI().toString()
  }

  // Example in which user 100 and user 101 have both given item 0 5 stars.
  // Each has also reviewed another item and given that item a different rating, (this ensures
  // that the mean-adjusted rating is not a zero).
  val version: Long = 0L
  val slices: List[(EntityId, Seq[Cell[Double]])] = List(
    // User 0 gave item 0 a 5.0 and item 1 a 0.0
    (EntityId(100L), List(
        Cell[Double]("ratings", "10", version, 5.0),
        Cell[Double]("ratings", "11", version, 5.0),
        Cell[Double]("ratings", "20", version, 0.0))),
    // User 1 gave item 0 a 5.0 and item 2 a 0.0
    (EntityId(101L), List(
        Cell[Double]("ratings", "10", version, 5.0),
        Cell[Double]("ratings", "11", version, 5.0),
        Cell[Double]("ratings", "21", version, 0.0))))

  /* Results we should see:

    - User 100 average rating is 3.3
    - User 101 average rating is 3.3

    - Adjusted ratings for (user,item) are:

      - 100,10 -> 1.7
      - 100,11 -> 1.7
      - 100,20 -> -3.3
      - 101,10 -> 1.7
      - 101,11 -> 1.7
      - 101,21 -> -3.3

  - Dot product between items 10 and 11:

                  1.7 * 1.7 + 1.7 * 1.7
                  ---------------------
    sqrt(1.7*1.7 + 1.7*1.7) * sqrt(1.7*1 + 1.7*1.7)

    = 1.0

  - The only other valid similarities are between 10/11 and 20/21.  All of them have the same
    score:

    1.7 * -3.3 / ( sqrt(1.7*1.7 + 1.7*1.7) + sqrt(-3.3*-3.3) ) = -0.707

  - All of the negative ratings should be filtered out.

   */

  // Final output should look like:
  // itemA: Long, itemB: Long, similarity: Double
  def validateOutput(output: mutable.Buffer[(Long, Long, Double)]): Unit = {
    def doubleComp(a: Double, b: Double) = abs(a-b) < 0.01

    // Convert to a Map so that we can check for the value of a particular pair
    val pairs2scores = output
        .toList
        .map{ x: (Long, Long, Double) => {
          val (itemA, itemB, score) = x
          ((itemA, itemB), score)
        }}
        .toMap

    //assert(pairs2scores.size == 5)
    assert(pairs2scores.size == 1)

    assert(pairs2scores.contains((10,11)))
    assert(pairs2scores((10,11)) ~= 1.0)

    /*
    assert(pairs2scores.contains((10,20)))
    assert(pairs2scores.contains((10,21)))
    assert(pairs2scores.contains((11,20)))
    assert(pairs2scores.contains((11,21)))

    assert(pairs2scores((10,20)) ~= -0.707)
    assert(pairs2scores((11,20)) ~= -0.707)
    assert(pairs2scores((10,21)) ~= -0.707)
    assert(pairs2scores((11,21)) ~= -0.707)
    */

  }
  def validateRecordOutput(output: mutable.Buffer[Any]): Unit = {
    logger.debug("-----------------------------------------------")
    logger.debug(output.getClass.toString)
  }

  test("Similarity of two items with same users and same ratings is 1") {
    //logger.debug("Running this test!")
    //logger.debug("-----------------------------------------------")
    // TODO: What is "_" here for?  Args below?
    val jobTest = JobTest(new ItemSimilarityCalculator(_))
        .arg("table-uri", uri)
        .source(KijiInput(uri, Map(ColumnFamilyInputSpec("ratings") -> 'ratingInfo)), slices)
        .sink(Tsv("all-similarities"))(validateOutput)
        .sink(Tsv("sorted-similarities"))(validateRecordOutput)

    // Run in local mode.
    jobTest.run.finish
    // Run in hadoop mode.
    //jobTest.runHadoop.finish
  }
}

