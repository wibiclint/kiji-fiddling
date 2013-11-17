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
abstract class ItemItemSuite extends KijiSuite {
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
  val userRatingsSlices: List[(EntityId, Seq[Cell[Double]])] = List(
    (EntityId(100L), List(
        //                     item           score
        Cell[Double]("ratings", "10", version, 5.0),
        Cell[Double]("ratings", "11", version, 5.0),
        Cell[Double]("ratings", "20", version, 0.0))),
    (EntityId(101L), List(
        Cell[Double]("ratings", "10", version, 5.0),
        Cell[Double]("ratings", "11", version, 5.0),
        Cell[Double]("ratings", "21", version, 0.0))))

  val kijiInputUserRatings = KijiInput(userRatingsUri, Map(ColumnFamilyInputSpec("ratings") -> 'ratingInfo))
  val kijiOutputItemSimilarities = KijiOutput(itemItemSimilaritiesUri,
      Map('mostSimilar -> QualifiedColumnOutputSpec(
          "most_similar",
          "most_similar",
          specificClass = classOf[AvroSortedSimilarItems]
          )))

}
