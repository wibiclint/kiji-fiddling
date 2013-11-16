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
import scala.collection.JavaConverters.seqAsJavaListConverter

import cascading.pipe.Pipe
import cascading.pipe.joiner.LeftJoin
import com.twitter.scalding._
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import org.kiji.express._
import org.kiji.express.flow._

import org.kiji.express.item_item_cf.avro._

// Without this, writing functions that operate on multiple fields at once is terrible...
//import com.twitter.scalding.FunctionImplicits._

/**
 * Calculate item-item similarity.
 *
 * Get mean rating / user:
 * - Read in all of the data from the table
 * - Group by user
 * - Get the mean
 * - Create a (user, mean) stream
 *
 * Get the mean-adjusted ratings for all of the items
 * - Read in all of the data from the table
 * - Group by user
 * - Join with the (tiny) (user, mean) stream
 * - Subtract the mean from all of the ratings
 * - Create a (user, item, normalized-rating) stream
 *
 * Get the cosine similarity of all of the pairs for items
 *
 * @param args passed in from the command line.
 */
class ItemSimilarityCalculator(args: Args) extends KijiJob(args) {

  val logger: Logger = LoggerFactory.getLogger(classOf[ItemSimilarityCalculator])

  def extractItemIdAndRating(slice: Seq[Cell[Double]]): Seq[(Long,Double)] = {
    slice.map { cell => (cell.qualifier.toLong, cell.datum) }
  }

  def getRatingPairProducts(itemsAndRatings: List[(Long, Double)]) = {
    for {
      (itemA: Long, ratingA: Double) <- itemsAndRatings
      (itemB: Long, ratingB: Double) <- itemsAndRatings
      if (itemA < itemB)
    } yield (itemA, itemB, ratingA * ratingB)

  }

  //------------------------------------------------------------------------------------------------
  // Code to create various pipes

  /**
   * Create a basic pipe with the raw input data.
   */
  def createUserRatingsPipe: Pipe = {
    // Read all of the data out of the user ratings table
    val userRatingsPipe = KijiInput(
        tableUri = args("ratings-table-uri"),
        columns = Map(ColumnFamilyInputSpec("ratings") -> 'ratingInfo))
        .read

        // Extract the userIds
        .map('entityId -> 'userId) { eid: EntityId => eid.components(0) }

        // Extract the ratings
        .flatMap('ratingInfo -> ('itemId, 'rating)) { extractItemIdAndRating }

        .project('userId, 'itemId, 'rating)

        // TODO: Make this into a macro / or a method???
        /*
        .map(('userId, 'itemId, 'rating) -> 'foo) { x: (Long, Long, Double) => {
          val (userId: Long, itemId: Long, rating: Double) = x
          logger.debug("userId: " + userId + " itemId: " + itemId + " rating: " + rating)
          0
        }}
        .discard('foo)
        */

    userRatingsPipe
  }

  /**
   * Create a pipe with the mean-adjusted user ratings.
   */
  def createMeanAdjustedUserRatingsPipe(userRatingsPipe: Pipe): Pipe = {

    // Group by user and compute the mean ratings
    val userMeansPipe = userRatingsPipe
        .groupBy('userId) { _.average('rating -> 'avgRating) }

    // Create stream with user ratings adjusted by the user's mean
    val adjustedUserRatingsPipe = userRatingsPipe
        .joinWithSmaller('userId -> 'userId, userMeansPipe)
        .map(('rating, 'avgRating) -> 'rating) { x: (Double, Double) => {x._1 - x._2 }}

    adjustedUserRatingsPipe
  }

  /**
   * Create a pipe with the cosine similarities between items.
   *
   * Compute the cosine similarity in three steps:
   *
   * 1. Compute the dot products between different item vectors.
   *
   * 2. Compute the magnitude of each item vector.
   *
   * 3. For every item pair, divide the dot product by the product of the vector magnitudes.
   *
   * The tricky part is to efficiently get the dot products.  Below, we group all of the item
   * ratings by user (since we need only compute dot products of items with overlapping users who
   * have rated them).  We then get all of the pairs of the possible pairs of ratings per user.  We
   * order the pairs (itemA, itemB) such that the itemId of itemA &lt; the itemId of itemB.
   *
   * To scale this for a system in which a particular user may have so many ratings that this
   * crossproduct does not fit in main memory, we might have to do something smarter!
   *
   * @param adjustedUserRatingsPipe has fields `userId`, `itemId`, and `rating`.
   */
  def createItemItemSimilaritiesPipe(adjustedUserRatingsPipe: Pipe): Pipe = {

    def createDotProductPipe: Pipe = {
        adjustedUserRatingsPipe
            // This is just to get things to run faster...
            .groupBy('userId) { _.toList[(Long, Double)](('itemId, 'rating) -> 'ratingList) }
            // Now compute the products for every pair of items and group by itemA, itemB.
            // Note that we no longer need the userId.
            .flatMapTo('ratingList -> ('itemA, 'itemB, 'dotProductTerm)) { getRatingPairProducts }
    }

    def createItemVectorNormPipe: Pipe = {
        adjustedUserRatingsPipe
            // Compute the dot product of this vector with itself = sum of squares of ratings
            .groupBy('itemId) { _.foldLeft('rating -> 'normSquared)(0.0) {
              (sumSoFar: Double, nextRating: Double) => sumSoFar + nextRating * nextRating
            }}
            .project('itemId, 'normSquared)
            .map('normSquared -> 'norm) { normSquared: Double => sqrt(normSquared) }
            .discard('normSquared)
    }

    // Get the pipes for the dot products and the norms and join them on the pairs of items used to
    // get the dot products.
    val dotProductPipe = createDotProductPipe
    val itemVectorNormPipe = createItemVectorNormPipe

    val simPipe = dotProductPipe

        // Compute the sum of all of similarity terms
        .groupBy('itemA, 'itemB) { _.sum('dotProductTerm -> 'dotProduct) }
        .project('itemA, 'itemB, 'dotProduct)

        // Do a left join here because we care only about the norms of items that show up in a
        // similarity pair (e.g., If an item has been reviewed by only one user, and that user never
        // reviewed anything else, then that item will not have a similarity rating to any other
        // items).
        .joinWithSmaller(
            'itemA -> 'itemId,
            itemVectorNormPipe,
            joiner = new LeftJoin)
        .discard('itemId)
        .rename('norm -> 'normA)
        .joinWithSmaller(
            'itemB -> 'itemId,
            itemVectorNormPipe,
            joiner = new LeftJoin)
        .discard('itemId)
        .rename('norm -> 'normB)

        .mapTo(('itemA, 'itemB, 'dotProduct, 'normA, 'normB) -> ('itemA, 'itemB, 'similarity)) {

          x: (Long, Long, Double, Double, Double) => {
            val (itemA, itemB, dotProduct, normA, normB) = x
            (itemA, itemB, dotProduct / (normA * normB))
        }}


    // Filter out any negative similarities
    val positiveSims = simPipe
        .filter('similarity) { sim: Double => sim > 0 }

    positiveSims
  }

  /**
   * Sort (itemA, itemB, similarity) tuples by similarity (for a given pair of tuples) and store in
   * Avro records.  Remember for a tuple (itemA, itemB, similarity) to populate the similarity
   * vectors for itemA *and* for itemB.
   *
   */
  def createSimilarityRecordsPipe(simPipe: Pipe): Pipe = {

    // Create similarities from itemB -> itemA from itemA -> itemB tuples
    val twistedSimPipe = simPipe
      .map(('itemA, 'itemB, 'similarity) -> ('itemA, 'itemB, 'similarity))
      { x: (Long, Long, Double) => (x._2, x._1, x._3) }

    // Combine the two streams, giving us all combinations of item pairs
    val allSimPipe = simPipe ++ twistedSimPipe

    // Group by the first item in the pair and sort to get a sorted list of item similarities
    allSimPipe
        .groupBy('itemA) {
          _.sortWithTake(('itemB, 'similarity) -> 'topSimPairs, 50)
              { (x: (Long, Double), y: (Long, Double)) => x._2 > y._2 } }
          // Now we have tuples of ('itemA, 'topSimPairs = List[(Long, Double)]

        .map('topSimPairs -> 'mostSimilar) { x: List[(Long, Double)] => {
          val simList = x.map { y: (Long, Double) => new AvroItemSimilarity(y._1, y._2) }
          new AvroSortedSimilarItems(simList.asJava)
        }}
        .rename('itemA, 'item)
        .project('item, 'mostSimilar)
  }

  // Read in user ratings for various items
  val userRatingsPipe = createUserRatingsPipe

  // Calculate the mean rating of each user and normalize the ratings accordingly
  val meanAdjustedUserRatingsPipe = createMeanAdjustedUserRatingsPipe(userRatingsPipe)

  // Compute cosine similarity between pairs of item vectors
  // (Includes only positive similarities)
  val simPipe = createItemItemSimilaritiesPipe(meanAdjustedUserRatingsPipe)

  // Sort by similarity and create avro records to store in a Kiji table
  val simRecordsPipe = createSimilarityRecordsPipe(simPipe)
      .map('item -> 'entityId) { item: Long => EntityId(item) }
      .project('entityId, 'mostSimilar)

  simRecordsPipe.write(KijiOutput(
    tableUri = args("similarity-table-uri"),
    columns = Map(
      'mostSimilar -> QualifiedColumnOutputSpec("most_similar", "most_similar"))))
  /*
  // If you do it this when instead then you get a crazy serialization error!
  val kijiOutput = KijiOutput(
    tableUri = args("similarity-table-uri"),
    columns = Map(
      'mostSimilar -> QualifiedColumnOutputSpec("most_similar", "most_similar")))

  simRecordsPipe.write(kijiOutput)
  */

  // Write the similarities to a Kiji table for later computation
  //simRecordsPipe.write(Tsv("sorted-similarities"))
}
