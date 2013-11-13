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

@RunWith(classOf[JUnitRunner])
class ItemSimilaritySuite extends KijiSuite {
  val logger: Logger = LoggerFactory.getLogger(classOf[ItemSimilaritySuite])

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



   */

  // Final output should look like:
  // itemA: Long, itemB: Long, similarity: Double
  def validateOutput(output: mutable.Buffer[(Long, Long, Double)]): Unit = {
    val myVals = output.toList
    logger.debug("Output of item-item similarity pipe = " + myVals)
    //assert(myVals.size == 2)
    output.foreach { case (itemA: Long, itemB: Long, rating: Double) =>
      println(itemA + " " + itemB + " " + rating)
    }
  }

  test("Similarity of two items with same users and same ratings is 1") {
    logger.debug("Running this test!")
    logger.debug("-----------------------------------------------")
    // TODO: What is "_" here for?  Args below?
    val jobTest = JobTest(new ItemSimilarity(_))
        .arg("table-uri", uri)
        .source(KijiInput(uri, Map(ColumnFamilyInputSpec("ratings") -> 'ratingInfo)), slices)
        .sink(Tsv("foo"))(validateOutput)

    // Run in local mode.
    jobTest.run.finish
    // Run in hadoop mode.
    //jobTest.runHadoop.finish
  }
}

