package gjum.minecraft.forge.morechunks;

import org.apache.logging.log4j.Level;

import java.util.*;
import java.util.stream.Collectors;

public class MoreChunks implements IMoreChunks {
    private final IMcGame game;
    private final IEnv env;
    private final Config config;
    private final String versionStr;

    public final IChunkServer chunkServer;

    private Pos2 lastRequestPlayerPos = new Pos2(Integer.MAX_VALUE, Integer.MAX_VALUE);

    private int serverRenderDistance; // cached because it's used a lot (e.g. on every incoming chunk)

    private long nextReconnectTime = 0;
    private long nextRetryInterval = 1000;

    public MoreChunks(IMcGame game, Config config, IEnv env, IChunkServer chunkServer, String versionStr) {
        this.game = game;
        this.config = config;
        this.env = env;
        this.versionStr = versionStr;
        this.chunkServer = chunkServer;

        onConfigChanged();
    }

    @Override
    public void onChunkServerConnected() {
        nextReconnectTime = 0;
        nextRetryInterval = 1000;

        if (!game.isIngame()) {
            chunkServer.disconnect(new ExpectedDisconnect("MoreChunks: No game running"));
            return;
        }

        chunkServer.sendStringMessage("mod.version=" + versionStr);
        chunkServer.sendStringMessage("game.address=" + game.getCurrentServerIp());
        chunkServer.sendPlayerDimension(game.getPlayerDimension());
        chunkServer.sendChunkLoadsPerSecond(config.getChunkLoadsPerSecond());
    }

    @Override
    public void onChunkServerDisconnected(DisconnectReason reason) {
        if (reason instanceof ExpectedDisconnect) return;
        env.log(Level.WARN, "ChunkServer disconnected: %s", reason);
        retryConnectChunkServer();
    }

    @Override
    public void onConfigChanged() {
        if (game.isIngame()) {
            serverRenderDistance = config.getMcServerConfig(game.getCurrentServerIp()).serverRenderDistance;
        }

        if (chunkServer.isConnected()) {
            chunkServer.sendChunkLoadsPerSecond(config.getChunkLoadsPerSecond());
        }
    }

    @Override
    public void onGameConnected() {
        env.log(Level.DEBUG, "Connected to game");

        if (game.wasPacketHandlerAlreadyInserted()) {
            return;
        }

        game.insertPacketHandler(this);

        serverRenderDistance = config.getMcServerConfig(game.getCurrentServerIp()).serverRenderDistance;

        retryConnectChunkServer();
    }

    @Override
    public void onGameDisconnected() {
        env.log(Level.DEBUG, "Game disconnected");
        if (chunkServer.isConnected()) {
            chunkServer.disconnect(new ExpectedDisconnect("MoreChunks: Game ending"));
        }
    }

    @Override
    public void onPlayerChangedDimension(int toDim) {
        env.log(Level.DEBUG, "Player moved to dimension %s", toDim);
        // TODO test
        chunkServer.sendPlayerDimension(toDim);
    }

    @Override
    public void onReceiveExtraChunk(Chunk chunk) {
        if (!game.isIngame()) {
            env.log(Level.WARN, "Received extra chunk at %s while not ingame", chunk.pos);
            return;
        }

        final int chunkDistance = 1 + chunk.pos.chebyshevDistance(game.getPlayerChunkPos());

        if (chunkDistance > game.getRenderDistance()) {
            env.log(Level.DEBUG, "Discarding too far extra chunk at %s", chunk.pos);
            return;
        }

        if (chunkDistance <= serverRenderDistance) {
            env.log(Level.DEBUG, "Discarding too close extra chunk at %s", chunk.pos);
            return;
        }

        game.runOnMcThread(() -> {
            game.unloadChunk(chunk.pos);
            game.loadChunk(chunk);
            unloadChunksOverCap();
        });
    }

    @Override
    public void onReceiveGameChunk(Chunk chunk) {
        if (chunkServer.isConnected()) {
            chunkServer.sendChunk(chunk);
            game.runOnMcThread(() -> {
                game.unloadChunk(chunk.pos);
                game.loadChunk(chunk);

                requestExtraChunks();
                unloadChunksOutsideRenderDistance();
                unloadChunksOverCap();
            });
        } else {
            env.log(Level.WARN, "chunkserver not connected when receiving game chunk");
        }
    }

    @Override
    public void onTick() {
        retryConnectChunkServer();
    }

    private void requestExtraChunks() {
        if (!chunkServer.isConnected()) return;
        if (lastRequestPlayerPos.equals(game.getPlayerChunkPos())) return; // TODO test

        List<Pos2> loadableChunks = getLoadableChunks();

        // apply limit
        // TODO sort loadable chunks by interest instead (e.g. within player's walking direction)
        loadableChunks = sortByPlayerDistance(loadableChunks);
        final int chunkLoadLimit = config.getMaxNumChunksLoaded() - game.getLoadedChunks().size();
        if (chunkLoadLimit <= 0) return; // TODO test
        loadableChunks = loadableChunks.stream().limit(chunkLoadLimit).collect(Collectors.toList());

        lastRequestPlayerPos = game.getPlayerChunkPos();

        chunkServer.sendChunksRequest(loadableChunks);
    }

    /**
     * Build a list of all chunk positions inside the client's render distance
     * that are neither loaded nor within server's' render distance.
     */
    private List<Pos2> getLoadableChunks() {
        final int rdClient = game.getRenderDistance();
        final Collection<Pos2> loadedChunks = game.getLoadedChunks();
        final Pos2 player = game.getPlayerChunkPos();

        final List<Pos2> loadable = new ArrayList<>();
        for (int x = player.x - rdClient; x <= player.x + rdClient; x++) {
            for (int z = player.z - rdClient; z <= player.z + rdClient; z++) {
                Pos2 chunk = new Pos2(x, z);

                if (1 + player.chebyshevDistance(chunk) <= serverRenderDistance) {
                    // do not load extra chunks inside the server's render distance,
                    // we expect the server to send game chunks here eventually
                    continue;
                }

                // TODO inefficient check? measure performance
                if (!loadedChunks.contains(chunk)) {
                    loadable.add(chunk);
                }
            }
        }
        return loadable;
    }

    /**
     * Sort chunk positions by taxicab distance to the player, in-place, ascending.
     *
     * @param chunks chunk positions to sort
     * @return sorted chunk positions
     */
    private List<Pos2> sortByPlayerDistance(List<Pos2> chunks) {
        final Pos2 player = game.getPlayerChunkPos();
        chunks.sort(Comparator.comparingDouble(player::taxicabDistance));
        return chunks;
    }

    private void retryConnectChunkServer() {
        if (chunkServer.isConnected()) return;
        if (!game.isIngame()) return;

        final String currentServerIp = game.getCurrentServerIp();
        if (currentServerIp == null) {
            env.log(Level.ERROR, "isIngame but currentServerIp == null");
            return;
        }

        final long now = env.currentTimeMillis();
        if (nextReconnectTime > now) return; // onTick() will retry on timeout

        nextReconnectTime = now + nextRetryInterval;
        nextRetryInterval *= 2;
        if (nextRetryInterval > 6000) nextRetryInterval = 6000; // TODO test

        final McServerConfig mcServerConfig = config.getMcServerConfig(currentServerIp);
        if (mcServerConfig == null) {
            return;
        }

        chunkServer.connect(mcServerConfig.chunkServerAddress, this);
    }

    private void unloadChunksOutsideRenderDistance() {
        final Pos2 player = game.getPlayerChunkPos();
        int renderDistance = game.getRenderDistance();
        ArrayList<Pos2> chunksToUnload = new ArrayList<>();
        for (Pos2 chunkPos : game.getLoadedChunks()) {
            if (1 + player.chebyshevDistance(chunkPos) > renderDistance) {
                chunksToUnload.add(chunkPos);
            }
        }
        for (Pos2 chunkPos : chunksToUnload) {
            game.unloadChunk(chunkPos);
        }
    }

    /**
     * Check if too many chunks are loaded, and unload the far away ones.
     */
    private void unloadChunksOverCap() {
        // TODO do not unload extra chunks over cap when only recently loaded, this could prevent flickering
        if (game.getLoadedChunks().size() > config.getMaxNumChunksLoaded()) {
            PriorityQueue<Pos2> closeChunks = new PriorityQueue<>(Comparator.comparingDouble(game.getPlayerChunkPos()::taxicabDistance));
            closeChunks.addAll(game.getLoadedChunks());
            closeChunks.stream()
                    .skip(config.getMaxNumChunksLoaded())
                    .forEach(game::unloadChunk);
        }
    }
}
