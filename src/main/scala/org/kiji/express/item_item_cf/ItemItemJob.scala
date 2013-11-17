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
import org.apache.avro.util.Utf8
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import org.kiji.express._
import org.kiji.express.flow._

import org.kiji.express.item_item_cf.avro._

/**
 * Contains common functionality for different phases in the item-item CF flow.
 *
 */
// TODO: Possibly make this into two separate classes, which abstract away a lot of horsing around
// with different tables
abstract class ItemItemJob(args: Args) extends KijiJob(args) {

  def extractItemIdAndRating(slice: Seq[Cell[Double]]): Seq[(Long,Double)] = {
    slice.map { cell => (cell.qualifier.toLong, cell.datum) }
  }

  /**
   * Get a pipe from the user-ratings table that looks like:
   * 'userId, 'itemId, 'rating
   */
  def createUserRatingsPipe(specificUser: Option[Long] = None): Pipe = {

    val userRatingsPipe: Pipe = KijiInput(
        tableUri = args("ratings-table-uri"),
        columns = Map(ColumnFamilyInputSpec("ratings") -> 'ratingInfo))
        .read

        // Extract the userIds
        .map('entityId -> 'userId) { eid: EntityId => eid.components(0) }

    val filteredUserRatingsPipe = specificUser match {
      case None => userRatingsPipe
      case Some(myUser: Long) => userRatingsPipe.filter('userId) { x: Long => x == myUser }
    }

    filteredUserRatingsPipe

        // Extract the ratings
        .flatMap('ratingInfo -> ('itemId, 'rating)) { extractItemIdAndRating }

        .project('userId, 'itemId, 'rating)

  }

  def extractItemIdAndSimilarity(slice: Seq[Cell[AvroSortedSimilarItems]]): Seq[(Long, Double)] = {
    slice.flatMap { cell => {
      // Get a Scala List of the similar items and similarities
      val topItems = cell.datum.getTopItems.asScala

      topItems.map { sim: AvroItemSimilarity => (sim.getItemId.toLong, sim.getSimilarity.toDouble) }
    }}}

  def createMostSimilarItemsPipe(specificItems: Option[Set[Long]] = None): Pipe = {
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
        .map('entityId -> 'itemId) { eid: EntityId => eid.components(0) }

    val filteredPipe = specificItems match {
      case None => mostSimilarPipe
      case Some(itemsSet) => mostSimilarPipe.filter('itemId)(itemsSet.contains)
    }


    filteredPipe
        // Extract out the itemId and similarity score for the similar items
        .flatMap('most_similar -> ('similarItem, 'similarity)) { extractItemIdAndSimilarity }

        .project('itemId, 'similarItem, 'similarity)
        .rename('itemId -> 'itemToScoreId)
  }


  /**
   * Read in the movie titles and attach them to another stream with movie IDs in a given field.
   *
   */
  def attachMovieTitles(pipe: Pipe, movieIdField: Symbol): Pipe = {
      KijiInput(args("titles-table-uri"), "info:title" -> 'title)
          // Get the movieIds from the entity IDs
          .map('entityId -> 'movieId) { eid: EntityId => eid.components(0) }
          // Extract the actual movie title
          .map('title -> 'title) { cellseq: Seq[Cell[CharSequence]] => {
            assert(cellseq.size == 1)
            cellseq.head.datum.toString
          }}
          .joinWithLarger('movieId -> movieIdField, pipe)
          .discard('movieId)
  }
}
