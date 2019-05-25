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

package eu.m6r.kicker.store;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;

public class ZookeeperClient implements AutoCloseable {

    public static String ZOOKEEPER_ROOT_PATH = "/kicker";

    private final Logger logger;
    private final ZooKeeper zooKeeper;

    public ZookeeperClient(final String zookeeperHosts) throws IOException {
        this.logger = LogManager.getLogger();
        this.zooKeeper = new ZooKeeper(zookeeperHosts, 30000, null);
    }

    public void createPath(final String path) throws IOException {
        StringBuilder currentPath = new StringBuilder();

        final List<String> paths =
                Arrays.stream(path.split("/")).filter(p -> !p.isEmpty())
                        .collect(Collectors.toList());

        for (String subPath : paths) {
            currentPath.append("/").append(subPath);

            try {
                if (zooKeeper.exists(currentPath.toString(), false) == null) {
                    zooKeeper.create(currentPath.toString(), null, ZooDefs.Ids.OPEN_ACL_UNSAFE,
                                     CreateMode.PERSISTENT);
                }
            } catch (InterruptedException | KeeperException e) {
                throw new IOException(e);
            }
        }
    }

    public String createEphemeralSequential(final String path, final String value)
            throws IOException {
        try {
            return zooKeeper.create(path, value.getBytes(),
                                    ZooDefs.Ids.OPEN_ACL_UNSAFE,
                                    CreateMode.EPHEMERAL_SEQUENTIAL);
        } catch (KeeperException | InterruptedException e) {
            throw new IOException(e);
        }
    }

    public String createSequential(final String path, final String value) throws IOException {
        try {
            return zooKeeper.create(path, value.getBytes(),
                                    ZooDefs.Ids.OPEN_ACL_UNSAFE,
                                    CreateMode.PERSISTENT_SEQUENTIAL);
        } catch (KeeperException | InterruptedException e) {
            throw new IOException(e);
        }
    }

    public String deleteChildren(final String path) throws IOException {
        try {
            for (final var nodePath : zooKeeper.getChildren(path, false)) {
                final var stats = zooKeeper.exists(nodePath, false);
                zooKeeper.delete(nodePath, stats.getVersion()));
            }
        } catch (KeeperException | InterruptedException e) {
            throw new IOException(e);
        }
    }

    public boolean checkLock(final String lockPath, final Watcher watcher)
            throws KeeperException, InterruptedException {
        final String parentPath = Paths.get(lockPath).getParent().toString();

        final List<String> children = zooKeeper.getChildren(parentPath, watcher);
        return children.stream()
                .min(String::compareTo)
                .map(string -> {
                    logger.info("Current min lock node: {}", string);
                    return String.format("%s/%s", parentPath, string).equals(lockPath);
                })
                .orElse(false);
    }

    public void writeNode(final String path, final byte[] value) throws IOException {
        try {
            final Stat stat = zooKeeper.exists(path, false);

            if (stat == null) {
                zooKeeper.create(path, value, ZooDefs.Ids.OPEN_ACL_UNSAFE,
                                 CreateMode.PERSISTENT);
            } else {
                zooKeeper.setData(path, value, stat.getVersion());
            }
        } catch (InterruptedException | KeeperException e) {
            throw new IOException(e);
        }
    }

    public void updateNode(final String path, final byte[] value, final int lastVersion)
            throws IOException, ZNodeDoesNotExistException, VersionMismatchException {
        try {
            final Stat stat = zooKeeper.exists(path, false);

            if (stat == null) {
                throw new ZNodeDoesNotExistException(path);
            }

            try {
                zooKeeper.setData(path, value, lastVersion);
            } catch (KeeperException e) {
                if (e.code() == KeeperException.Code.BADVERSION) {
                    throw new VersionMismatchException(path, lastVersion);
                }
                throw e;
            }
        } catch (InterruptedException | KeeperException e) {
            throw new IOException(e);
        }
    }

    public void deleteNode(final String path) throws IOException {
        try {
            final Stat stat = zooKeeper.exists(path, false);
            if (stat != null) {
                zooKeeper.delete(path, stat.getVersion());
            }
        } catch (InterruptedException | KeeperException e) {
            throw new IOException(e);
        }
    }

    public byte[] readNode(final String path) throws IOException, ZNodeDoesNotExistException {
        try {
            return zooKeeper.getData(path, null, null);
        } catch (InterruptedException e) {
            throw new IOException(e);
        } catch (KeeperException e) {
            if (e.code() == KeeperException.Code.NONODE) {
                throw new ZNodeDoesNotExistException(path);
            }
            throw new IOException(e);
        }
    }

    @Override
    public void close() throws Exception {
        zooKeeper.close();
    }

    public static class ZNodeDoesNotExistException extends Exception {

        ZNodeDoesNotExistException(final String path) {
            super(String.format("ZNode with path %s does not exists.", path));
        }
    }

    public static class VersionMismatchException extends Exception {

        VersionMismatchException(final String path, final int expectedVersion) {
            super(String.format("Expected version %d of path %s do not match",
                                expectedVersion, path));
        }
    }


}
