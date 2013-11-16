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
import scala.collection.JavaConverters._

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
import org.apache.commons.io.IOUtils
import java.io.InputStream

import org.kiji.express._
import org.kiji.express.flow._
import org.kiji.express.flow.util.Resources.doAndRelease
import org.kiji.schema.KijiTable
import org.kiji.schema.util.InstanceBuilder

import org.kiji.schema.Kiji
import org.kiji.schema.KijiURI
import org.kiji.schema.layout.KijiTableLayout

import org.kiji.schema.shell.api.Client
import org.kiji.express.item_item_cf.avro._

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
  def readResource(resourcePath: String): String = {
    try {
      val istream: InputStream =
        classOf[ItemSimilarityCalculatorSuite].getClassLoader().getResourceAsStream(resourcePath)
      val content: String = IOUtils.toString(istream)
      istream.close
      content
    } catch {
      case e: Exception => "Problem reading resource \"" + resourcePath + "\""
    }
  }

  def makeTestKijiTableFromDDL(
    ddlName: String,
    tableName: String,
    instanceName: String = "default_%s".format(counter.incrementAndGet())
  ): KijiTable = {

    // Create the table
    val ddl: String = readResource(ddlName)

    // Create the instance
    val kiji: Kiji = new InstanceBuilder(instanceName).build()
    val kijiUri: KijiURI = kiji.getURI()

    val client: Client = Client.newInstance(kijiUri)
    client.executeUpdate(ddl)

    // Get a pointer to the instance
    //val kiji: Kiji = Kiji.Factory.open(kijiUri)
    val table: KijiTable = kiji.openTable(tableName)
    kiji.release()
    return table
  }

  implicit def add_~=(d:Double) = new WithAlmostEquals(d)
  implicit val precision = Precision(0.001)
  val logger: Logger = LoggerFactory.getLogger(classOf[ItemSimilarityCalculatorSuite])

  // Create test versions of the tables for user ratings and for similarities
  val userRatingsUri: String = doAndRelease(
      makeTestKijiTable(layout("user_ratings.json")))
      { table: KijiTable => table.getURI().toString() }

  val itemItemSimilaritiesUri: String = doAndRelease(
      makeTestKijiTableFromDDL(
        ddlName = "item_item_similarities.ddl",
        tableName = "item_item_similarities"))
      { table: KijiTable => table.getURI().toString() }

  // Example in which user 100 and user 101 have both given item 0 5 stars.
  // Each has also reviewed another item and given that item a different rating, (this ensures
  // that the mean-adjusted rating is not a zero).
  val version: Long = 0L
  val slices: List[(EntityId, Seq[Cell[Double]])] = List(
    (EntityId(100L), List(
        //                     item           score
        Cell[Double]("ratings", "10", version, 5.0),
        Cell[Double]("ratings", "11", version, 5.0),
        Cell[Double]("ratings", "20", version, 0.0))),
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

  def validateOutput(output: mutable.Buffer[(EntityId, List[Cell[AvroSortedSimilarItems]])]): Unit = {
    assert(output.size === 2)
    val pairs2scores = output
        .map { x: (EntityId, List[Cell[AvroSortedSimilarItems]]) => {
          val (eid, simItems) = x
          assert(simItems.size === 1)
          val itemA: Long = eid.components(0).asInstanceOf[Long]
          val topItems = simItems.head.datum.getTopItems.asScala
          assert(topItems.size === 1)
          val sim: AvroItemSimilarity = topItems.head
          val itemB: Long = sim.getItemId
          val similarity: Double = sim.getSimilarity
          ((itemA, itemB), similarity)
        }}
        .toMap
    assert(pairs2scores.contains((10,11)))
    assert(pairs2scores((10,11)) ~= 1.0)

    assert(pairs2scores.contains((11,10)))
    assert(pairs2scores((11,10)) ~= 1.0)
  }

  test("Similarity of two items with same users and same ratings is 1") {
    val jobTest = JobTest(new ItemSimilarityCalculator(_))
        .arg("ratings-table-uri", userRatingsUri)
        .arg("similarity-table-uri", itemItemSimilaritiesUri)
        .source(
            KijiInput(
                userRatingsUri,
                Map(ColumnFamilyInputSpec("ratings") -> 'ratingInfo)),
            slices)
        .sink(KijiOutput(itemItemSimilaritiesUri,
            Map('mostSimilar -> QualifiedColumnOutputSpec(
                "most_similar",
                "most_similar",
                specificClass = classOf[AvroSortedSimilarItems]
                ))))
            {validateOutput}

    // Run in local mode.
    jobTest.run.finish
    // Run in hadoop mode.
    //jobTest.runHadoop.finish
  }
}

