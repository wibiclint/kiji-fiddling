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

import scala.math.sqrt
import scala.collection.JavaConverters._

import cascading.pipe.Pipe
import cascading.pipe.joiner.LeftJoin
import com.twitter.scalding._
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import org.kiji.express._
import org.kiji.express.flow._

import org.kiji.express.item_item_cf.avro._

/**
 * Calculate the score for an item for a user by taking the weighted average of that user's rating
 * for the K most similar items to the item in question.
 *
 * @param args passed in from the command line.
 */
class ItemScorer(args: Args) extends KijiJob(args) {

  val user = args("user").toLong
  val userEntityId = EntityId(user)
  val item = args("item").toLong
  val itemEntityId = EntityId(item)

  def extractItemIdAndSimilarity(slice: Seq[Cell[AvroSortedSimilarItems]]): Seq[(Long, Double)] = {
    slice.flatMap { cell => {
      // Get a Scala List of the similar items and similarities
      val topItems = cell.datum.getTopItems.asScala

      topItems.map { sim: AvroItemSimilarity => (sim.getItemId.toLong, sim.getSimilarity.toDouble) }
    }}}

  // Get the most similar items to this item
  // Extract them out of the AvroSortedSimilarItems
  val mostSimilarPipe = KijiInput(
      tableUri = args("similarity-table-uri"),
      columns = Map(
        QualifiedColumnInputSpec(
            "most_similar",
            "most_similar",
            specificRecord = classOf[AvroSortedSimilarItems]
      ) -> 'most_similar))
      // We care about only the data for one item
      .filter('entityId) { eid: EntityId => eid == itemEntityId }
      // Extract out the itemId and similarity score for the similar items
      .flatMap('most_similar -> ('otherItem, 'similarity)) { extractItemIdAndSimilarity }

      .write(Tsv("foo"))

  // Get a list of all of the items that the user has rated

  // Select only the most similar items that the user has rated

  // Sort, and then take the top K
}
