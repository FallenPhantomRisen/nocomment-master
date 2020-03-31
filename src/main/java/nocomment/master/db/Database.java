package nocomment.master.db;

import nocomment.master.Server;
import nocomment.master.util.OnlinePlayer;
import org.apache.commons.dbcp2.BasicDataSource;

import java.sql.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public class Database {
    private static BasicDataSource pool;

    static {
        System.out.println("Connecting to database...");
        pool = new BasicDataSource();
        pool.setUsername("nocom");
        pool.setPassword("6bf40e917cdc202f627398c433899a0cc9aa8880a6dc25aacc4342779eccd227");
        pool.setDriverClassName("org.postgresql.Driver");
        pool.setUrl("jdbc:postgresql://localhost:5432/nocom");
        pool.setInitialSize(1);
        pool.setMaxTotal(50);
        System.out.println("Connected.");
    }

    static void saveHit(Hit hit, CompletableFuture<Long> hitID) {
        long id;
        try (Connection connection = pool.getConnection();
             PreparedStatement stmt = connection.prepareStatement("INSERT INTO hits (created_at, x, z, dimension, server_id) VALUES (?, ?, ?, ?, ?) RETURNING id")) {
            stmt.setLong(1, hit.createdAt);
            stmt.setInt(2, hit.pos.x);
            stmt.setInt(3, hit.pos.z);
            stmt.setInt(4, hit.dimension);
            stmt.setInt(5, hit.serverID);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                id = rs.getLong("id");
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
            hitID.completeExceptionally(ex);
            throw new RuntimeException(ex);
        }
        hitID.complete(id); // only complete the future outside the try, when the connection is closed and the statement is committed!
    }

    public static void clearSessions(int serverID) {
        long mostRecent = mostRecentEvent(serverID);
        long setLeaveTo = mostRecent + 1; // range inclusivity
        if (setLeaveTo >= System.currentTimeMillis()) {
            // this will crash later, as soon as we try and add a player and the range overlaps
            // might as well crash early
            throw new RuntimeException("lol server clock went backwards");
        }
        try (Connection connection = pool.getConnection();
             PreparedStatement stmt = connection.prepareStatement("UPDATE player_sessions SET leave = ? WHERE range @> ? AND server_id = ?")) {
            stmt.setLong(1, setLeaveTo);
            stmt.setLong(2, Long.MAX_VALUE - 1); // must be -1 since postgres ranges are exclusive on the upper end
            stmt.setInt(3, serverID);
            stmt.executeUpdate();
        } catch (SQLException ex) {
            ex.printStackTrace();
            throw new RuntimeException(ex);
        }
    }

    private static long mostRecentEvent(int serverID) {
        try (Connection connection = pool.getConnection()) {
            long mostRecent;
            try (PreparedStatement stmt = connection.prepareStatement("SELECT MAX(created_at) FROM hits WHERE server_id = ?")) {
                stmt.setInt(1, serverID);
                try (ResultSet rs = stmt.executeQuery()) {
                    rs.next();
                    mostRecent = rs.getLong(1);
                }
            }
            try (PreparedStatement stmt = connection.prepareStatement("SELECT MAX(\"join\") FROM player_sessions WHERE range @> ? AND server_id = ?")) {
                stmt.setLong(1, Long.MAX_VALUE - 1); // must be -1 since postgres ranges are exclusive on the upper end
                stmt.setInt(2, serverID);
                try (ResultSet rs = stmt.executeQuery()) {
                    rs.next();
                    mostRecent = Math.max(mostRecent, rs.getLong(1));
                }
            }
            return mostRecent;
        } catch (SQLException ex) {
            ex.printStackTrace();
            throw new RuntimeException(ex);
        }
    }

    private static OptionalInt idForExistingPlayer(OnlinePlayer player) {
        try (
                Connection connection = pool.getConnection();
                PreparedStatement stmt = connection.prepareStatement(
                        player.hasUsername() ?
                                "UPDATE players SET username = ? WHERE uuid = ? RETURNING id"
                                : "SELECT id FROM players WHERE uuid = ?"
                )
        ) {
            if (player.hasUsername()) {
                stmt.setString(1, player.username);
                stmt.setObject(2, player.uuid);
            } else {
                stmt.setObject(1, player.uuid);
            }

            try (ResultSet existing = stmt.executeQuery()) {
                if (existing.next()) {
                    return OptionalInt.of(existing.getInt("id"));
                } else {
                    return OptionalInt.empty();
                }
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
            throw new RuntimeException(ex);
        }
    }

    public static int idForPlayer(OnlinePlayer player) {
        OptionalInt existing = idForExistingPlayer(player);
        if (existing.isPresent()) {
            return existing.getAsInt();
        }
        try (Connection connection = pool.getConnection();
             PreparedStatement stmt = connection.prepareStatement("INSERT INTO players (username, uuid) VALUES (?, ?) RETURNING id")) {
            stmt.setString(1, player.username);
            stmt.setObject(2, player.uuid);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getInt("id");
            }
        } catch (SQLException ex) {
            ex.printStackTrace(); // two threads ask for same player for the first time, at same time
            return idForExistingPlayer(player).getAsInt();
        }
    }

    private static OptionalInt idForExistingServer(String hostname) {
        try (Connection connection = pool.getConnection();
             PreparedStatement stmt = connection.prepareStatement("SELECT id FROM servers WHERE hostname = ?")) {
            stmt.setString(1, hostname);
            try (ResultSet existing = stmt.executeQuery()) {
                if (existing.next()) {
                    return OptionalInt.of(existing.getInt("id"));
                } else {
                    return OptionalInt.empty();
                }
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
            throw new RuntimeException(ex);
        }
    }

    public static int idForServer(String hostname) {
        OptionalInt existing = idForExistingServer(hostname);
        if (existing.isPresent()) {
            return existing.getAsInt();
        }
        try (Connection connection = pool.getConnection();
             PreparedStatement stmt = connection.prepareStatement("INSERT INTO servers (hostname) VALUES (?) RETURNING id")) {
            stmt.setString(1, hostname);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getInt("id");
            }
        } catch (SQLException ex) {
            ex.printStackTrace(); // two threads ask for same server for the first time, at same time
            return idForExistingServer(hostname).getAsInt();
        }
    }

    public static void addPlayers(int serverID, Collection<Integer> playerIDs, long now) {
        try (Connection connection = pool.getConnection();
             PreparedStatement stmt = connection.prepareStatement("INSERT INTO player_sessions (player_id, server_id, \"join\", leave) VALUES (?, ?, ?, NULL)")) {
            for (int playerID : playerIDs) {
                stmt.setInt(1, playerID);
                stmt.setInt(2, serverID);
                stmt.setLong(3, now);
                stmt.addBatch();
            }
            stmt.executeBatch();
        } catch (SQLException ex) {
            ex.printStackTrace();
            throw new RuntimeException(ex);
        }
    }

    public static void removePlayers(int serverID, Collection<Integer> playerIDs, long now) {
        try (Connection connection = pool.getConnection();
             PreparedStatement stmt = connection.prepareStatement("UPDATE player_sessions SET leave = ? WHERE range @> ? AND player_id = ? AND server_id = ?")) {
            for (int playerID : playerIDs) {
                stmt.setLong(1, now);
                stmt.setLong(2, Long.MAX_VALUE - 1); // must be -1 since postgres ranges are exclusive on the upper end
                stmt.setInt(3, playerID);
                stmt.setInt(4, serverID);
                stmt.addBatch();
            }
            stmt.executeBatch();
        } catch (SQLException ex) {
            ex.printStackTrace();
            throw new RuntimeException(ex);
        }
    }

    public static long createTrack(Hit initialHit, OptionalLong prevTrackID) {
        try (Connection connection = pool.getConnection();
             PreparedStatement stmt = connection.prepareStatement("INSERT INTO tracks (first_hit_id, last_hit_id, updated_at, prev_track_id, dimension, server_id) VALUES (?, ?, ?, ?, ?, ?) RETURNING id")) {
            long hitID = initialHit.getHitID().get();
            stmt.setLong(1, hitID);
            stmt.setLong(2, hitID);
            stmt.setLong(3, initialHit.createdAt);
            if (prevTrackID.isPresent()) {
                stmt.setLong(4, prevTrackID.getAsLong());
            } else {
                stmt.setNull(4, Types.BIGINT);
            }
            stmt.setInt(5, initialHit.dimension);
            stmt.setInt(6, initialHit.serverID);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getLong("id");
            }
        } catch (SQLException | InterruptedException | ExecutionException ex) {
            ex.printStackTrace();
            throw new RuntimeException(ex);
        }
    }

    public static void addHitToTrack(Hit hit, long trackID) {
        try (Connection connection = pool.getConnection()) {
            long hitID = hit.getHitID().get();
            try (PreparedStatement stmt = connection.prepareStatement("UPDATE hits SET track_id = ? WHERE id = ?")) {
                stmt.setLong(1, trackID);
                stmt.setLong(2, hitID);
                stmt.execute();
            }
            try (PreparedStatement stmt = connection.prepareStatement("UPDATE tracks SET last_hit_id = ?, updated_at = ? WHERE id = ?")) {
                stmt.setLong(1, hitID);
                stmt.setLong(2, hit.createdAt);
                stmt.setLong(3, trackID);
                stmt.executeUpdate();
            }
        } catch (SQLException | InterruptedException | ExecutionException ex) {
            ex.printStackTrace();
            throw new RuntimeException(ex);
        }
    }

    public static Set<Long> trackIDsToResume(Collection<Integer> playerIDs, Server server) {
        try (Connection connection = pool.getConnection()) {

            Set<Long> logoutTimestamps = new HashSet<>(); // set because there will be many duplicates

            try (PreparedStatement stmt = connection.prepareStatement("SELECT MAX(UPPER(range)) FROM player_sessions WHERE player_id = ? AND server_id = ?")) {
                for (int playerID : playerIDs) {
                    stmt.setInt(1, playerID);
                    stmt.setInt(2, server.serverID);
                    try (ResultSet rs = stmt.executeQuery()) {
                        rs.next();
                        logoutTimestamps.add(rs.getLong(1));
                    }
                }
            }

            Set<Long> trackIDsToResume = new HashSet<>(); // set because there will be many duplicates

            try (PreparedStatement stmt = connection.prepareStatement("SELECT id FROM tracks WHERE updated_at >= ? AND updated_at <= ? AND server_id = ?")) {
                for (long logoutTimestamp : logoutTimestamps) {
                    stmt.setLong(1, logoutTimestamp - 10_000);
                    stmt.setLong(2, logoutTimestamp + 10_000); // plus or minus 10 seconds
                    stmt.setInt(3, server.serverID);
                    try (ResultSet rs = stmt.executeQuery()) {
                        while (rs.next()) {
                            trackIDsToResume.add(rs.getLong(1));
                        }
                    }
                }
            }

            return trackIDsToResume;
        } catch (SQLException ex) {
            ex.printStackTrace();
            throw new RuntimeException(ex);
        }
    }

    public static List<TrackResume> resumeTracks(Collection<Long> trackIDs) {
        try (Connection connection = pool.getConnection();
             PreparedStatement stmt = connection.prepareStatement("SELECT hits.x, hits.z, hits.dimension FROM tracks INNER JOIN hits ON hits.id = tracks.last_hit_id WHERE tracks.id = ?")) {
            List<TrackResume> ret = new ArrayList<>();
            for (long trackID : trackIDs) {
                stmt.setLong(1, trackID);
                try (ResultSet rs = stmt.executeQuery()) {
                    rs.next();
                    ret.add(new TrackResume(rs.getInt("x"), rs.getInt("z"), rs.getInt("dimension"), trackID));
                }
            }
            return ret;
        } catch (SQLException ex) {
            ex.printStackTrace();
            throw new RuntimeException(ex);
        }
    }
}
