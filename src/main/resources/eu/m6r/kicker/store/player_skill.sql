SELECT player.id                                        AS id,
       name,
       avatarImage,
       SUM(games.games)                                 AS games,
       SUM(wins.wins)                                   AS wins,
       (trueSkillMean - 3 * trueSkillStandardDeviation) AS skill
FROM player
       INNER JOIN (
  SELECT player.id AS player_id
  FROM player
      LEFT JOIN tournament
  ON player.id IN
      (teama_player1_id, teama_player2_id, teamb_player1_id, teamb_player2_id)
      AND tournament.channel_id = :channelId
  GROUP BY player.id
  HAVING MAX(tournament.date) > (NOW() - INTERVAL '60 days')
) AS has_recent_tournaments ON player.id = has_recent_tournaments.player_id
       LEFT JOIN (
  SELECT player.id AS player_id, COUNT(tournament.id) AS games
  FROM player
         LEFT JOIN tournament
                   ON player.id IN
                      (teama_player1_id, teama_player2_id, teamb_player1_id, teamb_player2_id)
                     AND tournament.channel_id = :channelId
  GROUP BY player_id
) AS games ON player.id = games.player_id
       LEFT JOIN (
  SELECT player.id AS player_id, COUNT(*) AS wins
  FROM player
         LEFT JOIN (
    SELECT (CASE
              WHEN COALESCE(wins_a, 0) > COALESCE(wins_b, 0) THEN teama_player1_id
              ELSE teamb_player1_id END) AS winner_1,
           (CASE
              WHEN COALESCE(wins_a, 0) > COALESCE(wins_b, 0) THEN teama_player2_id
              ELSE teamb_player2_id END) AS winner_2
    FROM tournament
           LEFT JOIN (
      SELECT tournament_id, COUNT(matches_id) AS wins_a
      FROM tournament_match
             INNER JOIN match ON matches_id = match.id
      WHERE teama > teamb
      GROUP BY tournament_id
    ) AS team_a ON tournament.id = team_a.tournament_id
           LEFT JOIN (
      SELECT tournament_id, COUNT(matches_id) AS wins_b
      FROM tournament_match
             INNER JOIN match ON matches_id = match.id
      WHERE teama < teamb
      GROUP BY tournament_id
    ) AS team_b ON tournament.id = team_b.tournament_id
    WHERE channel_id = :channelId
  ) AS winners ON player.id IN (winner_1, winner_2)
  GROUP BY player_id
) AS wins ON player.id = wins.player_id
GROUP BY player.id
ORDER BY skill DESC;
