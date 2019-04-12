/*
 * This file is part of kicker (https://github.com/mbrtargeting/kicker).
 * Copyright (c) 2019 Jan Graßegger.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package eu.m6r.kicker;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import eu.m6r.kicker.models.Channel;
import eu.m6r.kicker.models.Match;
import eu.m6r.kicker.models.Player;
import eu.m6r.kicker.models.PlayerQueue;
import eu.m6r.kicker.models.PlayerSkill;
import eu.m6r.kicker.models.State;
import eu.m6r.kicker.models.Team;
import eu.m6r.kicker.models.Tournament;
import eu.m6r.kicker.slack.MessageWriter;
import eu.m6r.kicker.slack.models.Message;
import eu.m6r.kicker.store.Store;
import eu.m6r.kicker.trueskill.PlayerTrueSkillCalculator;
import eu.m6r.kicker.trueskill.TeamTrueSkillCalculator;
import eu.m6r.kicker.trueskill.TrueSkillCalculator;
import eu.m6r.kicker.utils.Properties;

public class Controller {

    private static Controller INSTANCE;

    private final Logger logger;
    private final TrueSkillCalculator playerTrueSkillCalculator;
    private final TrueSkillCalculator teamTrueSkillCalculator;
    private final PlayerQueues queues;
    private final RunningTournaments runningTournaments;
    private final String baseUrl;
    private final MessageWriter messageWriter;

    public static Controller getInstance() throws IOException {
        if (INSTANCE == null) {
            INSTANCE = new Controller();
        }

        return INSTANCE;
    }

    private Controller() throws IOException {
        this.logger = LogManager.getLogger();
        this.playerTrueSkillCalculator = new PlayerTrueSkillCalculator();
        this.teamTrueSkillCalculator = new TeamTrueSkillCalculator();

        final var properties = Properties.getInstance();
        final var zookeeperHosts = properties.zookeeperHosts();
        this.queues = new PlayerQueues(zookeeperHosts);
        this.runningTournaments = new RunningTournaments(zookeeperHosts);
        this.baseUrl = properties.getAppUrl();
        this.messageWriter = new MessageWriter(properties.getSlackToken());
    }

    public String joinChannel(final String slackId, final String slackName) {
        final var id = UUID.randomUUID().toString();
        final var channel = new Channel();
        channel.id = id;
        channel.name = slackName;
        channel.slackId = slackId;

        try (final var store = new Store()) {
            store.saveChannel(channel);
        }

        return id;
    }

    private void startTournament(final String channelId)
            throws TournamentRunningException, IOException {
        startTournament(channelId, true, 3);
    }

    public void startTournament(final String channelId, final boolean shuffle,
                                final int bestOfN)
            throws IOException, TournamentRunningException {
        var playerList = queues.get(channelId).queue;
        queues.clear(channelId);
        startTournament(channelId, shuffle, bestOfN, playerList);
    }

    public void startTournament(final String channelId, final int bestOfN,
                                final String playerA1, final String playerA2,
                                final String playerB1, final String playerB2)
            throws IOException, TournamentRunningException {

        final var playerList = new ArrayList<Player>();
        try (final var store = new Store()) {
            playerList.add(store.getPlayer(playerA1));
            playerList.add(store.getPlayer(playerA2));
            playerList.add(store.getPlayer(playerB1));
            playerList.add(store.getPlayer(playerB2));
        }

        startTournament(channelId, false, bestOfN, playerList);
    }

    private synchronized void startTournament(final String channelId, final boolean shuffle,
                                              final int bestOfN, List<Player> playerList)
            throws TournamentRunningException, IOException {
        if (hasRunningTournament(channelId)) {
            throw new TournamentRunningException();
        }

        if (shuffle) {
            Collections.shuffle(playerList);
            playerList = TrueSkillCalculator.getBestMatch(playerList);
        }

        try (final var store = new Store()) {
            final var teamA = store.getTeam(playerList.get(0), playerList.get(1));
            final var teamB = store.getTeam(playerList.get(2), playerList.get(3));
            final var channel = store.getChannel(channelId);
            final var tournament = new Tournament(bestOfN, teamA, teamB, channel);
            runningTournaments.save(tournament);

            final var message = String.format("A new game started:%n <@%s> <@%s> vs. <@%s> <@%s>",
                                              tournament.teamA.player1.id,
                                              tournament.teamA.player2.id,
                                              tournament.teamB.player1.id,
                                              tournament.teamB.player2.id);
            messageWriter.postMessage(tournament.channel.slackId, message);

        }


    }

    public synchronized void finishTournament(final String channelId,
                                              final boolean autoStartTournament)
            throws InvalidTournamentStateException, IOException, TournamentNotRunningException {
        final var runningTournament = this.runningTournaments.get(channelId);
        for (final var match : runningTournament.matches) {
            if (match.state == State.RUNNING) {
                throw new InvalidTournamentStateException("Can't finish tournament if matches"
                                                          + "are still running!");
            }
        }

        runningTournament.state = State.FINISHED;

        try (final var store = new Store()) {
            var updatedTournament =
                    playerTrueSkillCalculator.updateRatings(runningTournament);
            updatedTournament =
                    teamTrueSkillCalculator.updateRatings(updatedTournament);
            store.saveTournament(updatedTournament);
        }

        checkCrawlShaming(runningTournament);
        this.runningTournaments.clear(channelId);

        final var winner = runningTournament.winner();
        final var message = String.format("The game is over. Congratulations to <@%s> and <@%s>!",
                                          winner.player1.id,
                                          winner.player2.id);
        messageWriter.postMessage(runningTournament.channel.slackId, message);

        if (autoStartTournament && queues.get(channelId).isFull()) {
            try {
                startTournament(channelId);
            } catch (TournamentRunningException e) {
                logger.error("This should not happen.", e);
            }
        }
    }

    public List<Tournament> getTournaments(final String channelId) {
        try (final var store = new Store()) {
            return store.getTournaments(channelId);
        }
    }

    public List<Tournament> getTournaments(final String channelId, final int last) {
        try (final var store = new Store()) {
            return store.getLastTournaments(channelId, last);
        }
    }

    public String getChannelUrl(final String channelId) {
        final var appUrl = Properties.getInstance().getAppUrl();
        try (final var store = new Store()) {
            return appUrl + "/" + store.getChannel(channelId).id;
        }
    }

    public boolean hasRunningTournament(final String channelId) throws IOException {
        try {
            runningTournaments.get(channelId);
            return true;
        } catch (TournamentNotRunningException e) {
            return false;
        }
    }

    public Tournament getRunningTournament(final String channelId)
            throws IOException, TournamentNotRunningException {
        return runningTournaments.get(channelId);
    }

    public void updateTournament(final String channelId, final Tournament tournament)
            throws IOException, TournamentNotRunningException {
        final var storedTournament = runningTournaments.get(channelId);
        storedTournament.matches = tournament.matches;

        runningTournaments.save(storedTournament);
    }

    public boolean cancelRunningTournament(final String channelId) throws IOException {
        if (!hasRunningTournament(channelId)) {
            return false;
        }

        runningTournaments.clear(channelId);
        return true;
    }

    public void newMatch(final String channelId)
            throws InvalidTournamentStateException, TournamentNotRunningException, IOException {

        int teamAWins = 0;
        int teamBWins = 0;

        final var tournament = runningTournaments.get(channelId);

        for (final Match match : tournament.matches) {
            if (match.state == State.FINISHED) {
                if (match.teamA > match.teamB) {
                    teamAWins++;
                } else {
                    teamBWins++;
                }
            } else if (match.state == State.RUNNING) {
                throw new InvalidTournamentStateException("Can't create new match if matches "
                                                          + "are still running!");
            }
        }

        final var maxTeamWins = (tournament.bestOfN / 2) + 1;

        if (maxTeamWins <= Math.max(teamAWins, teamBWins)) {
            throw new InvalidTournamentStateException("Cannot create more matches than bestOfN.");
        }

        checkCrawlShaming(tournament);
        tournament.matches.add(new Match());
        runningTournaments.save(tournament);
    }

    public String getPlayersString(final String channelId) throws IOException {
        return queues.get(channelId).queue.stream().map(p -> String.format("<@%s>", p.id))
                .collect(Collectors.joining(", "));
    }

    public void addPlayer(final String channelId, final Player player)
            throws PlayerQueue.PlayerAlreadyInQueueException, PlayerQueue.TooManyUsersException,
                   IOException {

        try (final var store = new Store()) {
            final var storedPlayer = store.getPlayer(player);
            if (storedPlayer != null) {
                player.trueSkillMean = storedPlayer.trueSkillMean;
                player.trueSkillStandardDeviation = storedPlayer.trueSkillStandardDeviation;
            }
        }

        queues.add(channelId, player);

        if (queues.get(channelId).isFull() && !hasRunningTournament(channelId)) {
            try {
                startTournament(channelId);
            } catch (TournamentRunningException e) {
                logger.error("This should not happen", e);
            }
        }
    }

    public void resetPlayers(final String channelId) throws IOException {
        queues.clear(channelId);
    }

    public void removePlayer(final String channelId, final Player player) throws IOException {
        queues.remove(channelId, player);
        logger.info("Removed {} from the queues", player);
    }

    public List<Player> getPlayersInQueue(final String channelId) throws IOException {
        return queues.get(channelId).queue;
    }

    public List<PlayerSkill> playerSkills(final String channelId) {
        try (final var store = new Store()) {
            return store.playerSkills(channelId);
        }
    }

    public String getChannelId(String slackChannelId) {
        try (final var store = new Store()) {
            return store.getChannelBySlackId(slackChannelId).id;
        }
    }

    public String getChannelQRCodeUrl(final String channelId) {
        return String.format("%s/api/channel/%s/qrcode", baseUrl, channelId);
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    private void checkCrawlShaming(final Tournament tournament) {
        if (tournament.matches.isEmpty()) {
            return;
        }

        final var slackId = tournament.channel.slackId;
        final var lastMatch = tournament.matches.get(tournament.matches.size() - 1);

        final Team loosers;
        if (lastMatch.teamA == 0) {
            loosers = tournament.teamA;
        } else if (lastMatch.teamB == 0) {
            loosers = tournament.teamB;
        } else {
            return;
        }

        var messageString = String.format("<@%s> and <@%s> have to crawl. How embarrassing!!",
                                          loosers.player1.id, loosers.player2.id);

        final var message = new Message(slackId, messageString, null);
        messageWriter.postMessage(message);
    }

    public void recalculateTeamSkills(String channelId) {
        try (final Store store = new Store()) {
            store.resetTeamSkills();
        }
        final List<Tournament> tournamentList = getTournaments(channelId);
        Collections.reverse(tournamentList);
        for (final Tournament tournament : tournamentList) {
            try (final Store store = new Store()) {
                System.out.println("tournament = " + tournament);
                final Tournament updatedTournament = teamTrueSkillCalculator
                    .updateRatings(tournament);
                store.saveTournament(updatedTournament);
            }
        }
    }

    public static class TournamentRunningException extends Exception {

        TournamentRunningException() {
            super("A tournament is already running!");
        }
    }

    public static class TournamentNotRunningException extends Exception {

        TournamentNotRunningException() {
            super("No tournament is running!");
        }
    }

    public static class InvalidTournamentStateException extends Exception {

        InvalidTournamentStateException(final String message) {
            super(message);
        }
    }
}
