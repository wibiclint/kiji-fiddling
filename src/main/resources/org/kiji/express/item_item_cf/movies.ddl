CREATE TABLE user_ratings WITH DESCRIPTION 'User ratings of movies (movies are rows)'
  ROW KEY FORMAT (user_id LONG)
  WITH LOCALITY GROUP default WITH DESCRIPTION 'Main locality group' (
    FAMILY info WITH DESCRIPTION 'Non-rating information' (
      COLUMN id WITH SCHEMA "long" WITH DESCRIPTION 'UserId (hopefully not needed!)'
      ),
    MAP TYPE FAMILY ratings WITH SCHEMA "double" WITH DESCRIPTION 'MovieIds -> ratings'
  );

CREATE TABLE item_item_similarities
  WITH DESCRIPTION 'Top-M list of similar items, using adjusted cosine similarity'
  ROW KEY FORMAT (movie_id LONG)
  WITH LOCALITY GROUP default WITH DESCRIPTION 'Main locality group' (
    FAMILY info ( COLUMN movie_id WITH SCHEMA "long" ),
    MAP TYPE FAMILY most_similar WITH SCHEMA "double" WITH DESCRIPTION 'MovieIds -> similarity'
  );

