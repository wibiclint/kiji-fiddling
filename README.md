Kiji Express item/item collaborative filtering example
======================================================

Introduction
------------

In this example, we implement a top-N recommendation system using item-based
collaborative filtering.

Overview of the process:

  * Import the movie database into a kiji table
    - Group everything by user
  * Compute item similarities
    - Compute the average rating for each user
    - Use cosine similarity of items (after adjusting scores for average
      per-user ratings)
    - Here we may want to remember only the M most similar items for each item
      (don't keep entire similarity matrix)
  * Get a list of the k nearest neighbors for every item
    - Eventually this would be the "training" phase (?)
    - Store the results in this table or in another table?
  * Write score function
    - Input: An active user and the item in question
    - Get the list of the k nearest neighbors for the item in question (that the user has also
      rated)
    - The score is the sum of similarities * ratings (from the user), divided by the sum of all of
      the similarities
    - Output: A score for this item

Inspired by Mahout implementation: http://isabel-drost.de/hadoop/slides/collabMahout.pdf

Build the code
--------------
    mvn clean install


Download & Start Bento
----------------------
    cd kiji-bento-<whatever>
    source bin/kiji-env.sh
    bento start


Setup
-----
    export MOVIES_HOME=path/to/kiji-express-item-item-cf
    export KIJI_CLASSPATH=${MOVIES_HOME}/target/kiji-express-item-item-cf-XXX.jar
    export MOVIES_RESOURCES=${MOVIES_HOME}/src/main/resources/org/kiji/express/item_item_cf/


Install a Kiji instance.

    export KIJI=kiji://.env/item_item_cf
    kiji install --kiji=${KIJI}


Create the tables
-----------------
The `movies.ddl` file contains the DDL instructions for creating three tables:
- `user_ratings` contains all of the ratings that users have assigned to movies.  There is one row
  per user, and we use a map-type column family for the ratings (where the qualifier is the movie ID
  and the value of the column is the score).
- `item_item_similarities` contains the collaborative-filtering "model" (I may be misuing this term
  here...).  For every item in the system, this table contains a vector of the top-M most-similar
  items.  We store these vectors in specific Avro records.
- `movie_titles` maps movie IDs to actual titles.

We create the tables with the following command:

    ${KIJI_HOME}/schema-shell/bin/kiji-schema-shell --kiji=${KIJI} --file=$MOVIES_RESOURCES/movies.ddl

We then make sure that the table actually exists:

    ~MOVIES_HOME $ ../kiji-bento-chirashi/schema-shell/bin/kiji-schema-shell --kiji=$KIJI                
    Kiji schema shell v1.3.0
    Enter 'help' for instructions (without quotes).
    Enter 'quit' to quit.
    DDL statements must be terminated with a ';'
    schema> show tables;
    13/11/11 20:59:57 WARN org.apache.hadoop.util.NativeCodeLoader: Unable to load native-hadoop library for your platform... using builtin-java classes where applicable
    Table   Description
    ======  ========================================
    movies  User ratings of movies (movies are rows)
    schema> describe movies


Upload data to HDFS
-------------------
Next, we put all of our input data onto HDFS:

    hadoop fs -mkdir item_item_cf
    hadoop fs -copyFromLocal ${MOVIES_RESOURCES}/*.csv item_item_cf/

To check that the data actually got copied:

    ~MOVIES_HOME $ hadoop fs -ls item_item_cf
    13/11/11 21:04:18 WARN util.NativeCodeLoader: Unable to load native-hadoop library for your platform... using builtin-java classes where applicable
    Found 3 items
    -rw-r--r--   3 clint supergroup       3100 2013-11-11 21:03 item_item_cf/movie-titles.csv
    -rw-r--r--   3 clint supergroup    4388258 2013-11-11 21:03 item_item_cf/ratings.csv
    -rw-r--r--   3 clint supergroup     186304 2013-11-11 21:03 item_item_cf/users.csv


Import data into the tables
---------------------------

Next we run one job to import the user data into our Kiji table:

    express job target/kiji-express-item-item-cf-XXX.jar \
        org.kiji.express.item_item_cf.MovieImporter \
        --ratings item_item_cf/ratings.csv \
        --table-uri ${KIJI}/user_ratings \
        --hdfs

*Note* that there are no `=` signs between the option name and the option value for these jobs.
e.g., we say `--table-uri ${KIJI}/user_ratings`, and *not* `--table-uri=${KIJI}/user_ratings`.

And another to import the movie titles:

    express job target/kiji-express-item-item-cf-XXX.jar \
        org.kiji.express.item_item_cf.TitlesImporter \
        --titles item_item_cf/movie-titles.csv \
        --table-uri ${KIJI}/movie_titles \
        --hdfs

And then check that the data actually showed up:

    kiji scan ${KIJI}/user_ratings --max-rows=1
    kiji scan ${KIJI}/movie_titles --max-rows=1


Create the item-item similarity list
------------------------------------

This is where most of the excitement happens.  We compute the cosine similarities between all pairs
of items that have at least one common user who has reviewed them.  We then take the top M
most-similar items for a given item, sort them, and store them as a vector in our
`item_item_similarities` table:

    express job target/kiji-express-item-item-cf-XXX.jar \
        org.kiji.express.item_item_cf.ItemSimilarityCalculator \
        --ratings-table-uri ${KIJI}/user_ratings \
        --similarity-table-uri ${KIJI}/item_item_similarities \
        --model-size 50 \
        --hdfs

This job is quite a beast and can run for a very long time!


Run the scorer
--------------
Here we predict the score for a given user and item by taking a weighted average of all of the
similar items the user has rated.

    express job target/kiji-express-item-item-cf-XXX.jar \
        org.kiji.express.item_item_cf.ItemScorer \
        --ratings-table-uri ${KIJI}/user_ratings \
        --similarity-table-uri ${KIJI}/item_item_similarities \
        --titles-table-uri ${KIJI}/movie_titles \
        --users-and-items 1024:77 \
        --k 20 \
        --output-mode run \
        --hdfs

This Express job will write out the predicted scores for the user-item pairs that you provide in a
CSV file on HDFS:

    $ hadoop fs -cat score/part-00000
    13/11/18 11:16:12 WARN util.NativeCodeLoader: Unable to load native-hadoop library for your platform... using builtin-java classes where applicable
    1024,77,4.1968,Memento (2000)


Run the recommender
-------------------
We recommend items to a user based on what is in his or her shopping cart by taking the sum of all
of similar items across all items in the cart.

    express job target/kiji-express-item-item-cf-XXX.jar \
        org.kiji.express.item_item_cf.ItemRecommender \
        --similarity-table-uri ${KIJI}/item_item_similarities \
        --titles-table-uri ${KIJI}/movie_titles \
        --items 77 \
        --hdfs

Again, the Express job writes the results into a CSV on HDFS:

    $ hadoop fs -cat recommendation/part-00000
    13/11/18 11:19:02 WARN util.NativeCodeLoader: Unable to load native-hadoop library for your
    platform... using builtin-java classes where applicable
    180,0.0115,Minority Report (2002)
    585,0.0181,Monsters Inc. (2001)
    146,0.0203,Crouching Tiger Hidden Dragon (Wo hu cang long) (2000)
    862,0.0224,Toy Story (1995)
    12,0.0234,Finding Nemo (2003)
    121,0.0235,The Lord of the Rings: The Two Towers (2002)
    ...


Stop the BentoBox
-----------------
Now you are done and on your way to taking Jeff Bezos's lunch money!  Just stop the Bento Box:

    bento stop

and start deciding what newspaper company you want to buy with your billions!



Debugging tips
--------------
If you need to look at your system logs, try:

    http://localhost:50030/logs/userlogs/job_20131112153730549_0008/attempt_20131112153730549_0008_m_000000_0/syslog

Things to mention in the README (TODO)
--------------------------------------
* How to best debug?  How to print out streams?  Or parts of streams?
* Hard to debug stuff
    - I can write code that compiles, but fails because of what my schema is
    - How to get good debug messages?
    - Would be useful just to see what your streams look like
    - Scalding does have this - [pipe].debug
    - How to use log4j from Scalding?
* Can I always get a key back from an entity id?
* Would be good to get more clarity on exactly what I get from KijiInput when I
  use qualified and family specs
* Could not figure out how to operate on mixture of stuff from qualified column
  and column family
* Might be useful to have a series of small tutorials that get slowly more
  complicated (like in Cascading)
* Definitely need examples of how to do unit tests for Express!!!
    - Critical for users
    - Not really much documentation
    - Could use just some trivial examples: table (with map-type) -> table
      (with map-type), CSV -> CSV, etc.
    - Having to retype all of the source/sink stuff is terrible, need a way to
      get around this...
* Would be nice to have some examples of log statements
