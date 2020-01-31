/*
 * This file is part of MrFoosball (https://github.com/gesundkrank/mrfoosball).
 * Copyright (c) 2020 Jan Graßegger.
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

package de.gesundkrank.mrfoosball.api;

import java.io.IOException;
import java.util.List;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import de.gesundkrank.mrfoosball.Controller;
import de.gesundkrank.mrfoosball.Stats;
import de.gesundkrank.mrfoosball.api.annotations.CheckChannelId;
import de.gesundkrank.mrfoosball.models.PlayerSkill;
import de.gesundkrank.mrfoosball.models.TeamStat;

@Path("api/stats/{channelId: [0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89AB][0-9a-f]{3}-[0-9a-f]{12}}")
@CheckChannelId
public class StatsApi {

    private final Logger logger;
    private final Stats stats;
    private final Controller controller;

    public StatsApi() throws IOException {
        this.logger = LogManager.getLogger();
        this.stats = new Stats();
        this.controller = Controller.getInstance();
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public List<PlayerSkill> getStats(@PathParam("channelId") final String channelId) {
        try {
            return controller.playerSkills(channelId);
        } catch (Exception e) {
            logger.error("Failed to get skills", e);
            throw new WebApplicationException(e.getMessage());
        }
    }

    @GET
    @Path("teams")
    @Produces(MediaType.APPLICATION_JSON)
    public List<TeamStat> getTeamStats(@PathParam("channelId") final String channelId) {

        try {
            return stats.calcTeamStats(channelId);
        } catch (Exception e) {
            logger.error("Failed to calculate team stats", e);
            throw new WebApplicationException(e.getMessage());
        }
    }

}
