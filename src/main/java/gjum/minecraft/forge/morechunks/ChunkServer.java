package gjum.minecraft.forge.morechunks;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import net.minecraft.client.Minecraft;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.SPacketChunkData;
import net.minecraft.util.LazyLoadBase;
import org.apache.logging.log4j.Level;

import java.io.IOException;
import java.util.Collection;

import static net.minecraft.network.NetworkManager.CLIENT_EPOLL_EVENTLOOP;
import static net.minecraft.network.NetworkManager.CLIENT_NIO_EVENTLOOP;

public class ChunkServer implements IChunkServer {
    private final IEnv env;
    private IMoreChunks moreChunks;

    private Channel channel;

    private static final byte RECV_CHUNK_DATA = 0;
    private static final byte RECV_STATUS_MSG = 1;

    private static final byte SEND_CHUNK_DATA = 0;
    private static final byte SEND_STRING_MSG = 1;
    private static final byte SEND_CHUNKS_REQUEST = 2;

    public ChunkServer(IEnv env) {
        this.env = env;
    }

    @Override
    public synchronized void connect(String chunkServerAddress, IMoreChunks moreChunks) {
        if (isConnected()) {
            env.log(Level.WARN, "already connected");
            return;
        }

        if (channel != null) {
            channel.disconnect();
            env.log(Level.WARN, "already connecting");
        }

        channel = null;

        this.moreChunks = moreChunks;

        String[] hostPort = chunkServerAddress.split(":");
        String host = hostPort[0];
        int port = 44444;
        if (hostPort.length > 1) port = Integer.parseUnsignedInt(hostPort[1]);
        env.log(Level.DEBUG, "Connecting to %s:%d", host, port);

        Bootstrap bootstrap = new Bootstrap()
                .group(getLoopGroup().getValue())
                .channel(getChannelClass())
                .option(ChannelOption.SO_KEEPALIVE, true) // TODO what is this needed for?
                .handler(new ChannelInitializer<Channel>() {
                    protected void initChannel(Channel channel) {
                        channel.pipeline()
                                // every message is prepended with their length, so that we won't read partial messages or skip messages
                                .addLast("splitter", new LengthFieldBasedFrameDecoder(0x0fffffff, 0, 4, 0, 4))
                                .addLast("prepender", new LengthFieldPrepender(4))
                                .addLast("packet_handler", new ReceiverHandler());
                    }
                });
        ChannelFuture connectedF = bootstrap.connect(host, port);

        channel = connectedF.channel();

        connectedF.channel().closeFuture().addListener(closeF ->
                disconnect(new DisconnectReason("ChunkServer: Connection closed")));

        connectedF.addListener(connectF -> {
            if (!connectF.isSuccess()) {
                disconnect(new DisconnectReason("ChunkServer: Connection failed"));
                return;
            }

            moreChunks.onChunkServerConnected();
        });
    }

    @Override
    public void disconnect(DisconnectReason reason) {
        if (isConnected()) {
            channel.disconnect();
            channel = null;
        }

        moreChunks.onChunkServerDisconnected(reason);
    }

    @Override
    public String getConnectionInfo() {
        return channel.remoteAddress().toString();
    }

    @Override
    public boolean isConnected() {
        return channel != null && channel.isOpen();
    }

    /**
     * Packet format:
     * - messageType (byte): 0
     * - timestamp (long): seconds since unix epoch, for selecting the latest chunk on the server side
     * - chunkPacket (bytes): the full chunk packet in Minecraft's internal format (1.10/1.11)
     */
    @Override
    public void sendChunk(Chunk chunk) {
        if (!isConnected()) {
            env.log(Level.ERROR, "Trying to send chunk at %s while disconnected", chunk.pos);
            return;
        }

        env.log(Level.DEBUG, "Sending chunk to server: %s", chunk.pos);

        PacketBuffer msgBuf = new PacketBuffer(channel.alloc().buffer());
        msgBuf
                .writeByte(SEND_CHUNK_DATA)
                .writeLong(env.currentTimeMillis());

        final PacketBuffer chunkBuf = new PacketBuffer(channel.alloc().buffer());
        try {
            chunk.packet.writePacketData(chunkBuf);

            final ChunkData chunkData = new ChunkData(chunkBuf);
            chunkData.calculateHeightMap();
            final int topReplacedSection = moreChunks.decideUndergroundCutOff(chunkData);
            chunkData.replaceBottomSections(topReplacedSection);
            chunkData.serialize(msgBuf);
        } catch (IOException e) {
            env.log(Level.ERROR, "Failed to serialize chunk at %s", chunk.pos);
            e.printStackTrace();
        } finally {
            chunkBuf.release();
        }
        channel.writeAndFlush(msgBuf);
    }

    /**
     * Packet format:
     * - messageType (byte): 2
     * - numEntries (byte): how many chunks are requested
     * - chunkCoords (List<Long>): the coords of the requested chunks, combined into single Long values
     */
    @Override
    public void sendChunksRequest(Collection<Pos2> chunksPos) {
        if (!isConnected()) {
            env.log(Level.ERROR, "Trying to request %s chunks while disconnected", chunksPos.size());
            return;
        }

        env.log(Level.DEBUG, "Requesting %d chunks", chunksPos.size());

        ByteBuf buf = channel.alloc().buffer()
                .writeByte(SEND_CHUNKS_REQUEST);
        for (Pos2 pos : chunksPos) {
            buf.writeInt(pos.x).writeInt(pos.z);
        }
        channel.writeAndFlush(buf);
    }

    @Override
    public void sendChunkLoadsPerSecond(int chunkLoadsPerSecond) {
        sendStringMessage("mod.chunksPerSecond=" + chunkLoadsPerSecond);
    }

    @Override
    public void sendPlayerDimension(int dim) {
        sendStringMessage("game.dimension=" + dim);
    }

    /**
     * Packet format:
     * - messageType (byte): 1
     * - message (bytes): encoded message string
     */
    @Override
    public void sendStringMessage(String message) {
        if (!isConnected()) {
            env.log(Level.ERROR, "Trying to send string message while disconnected: \"%s\"", message);
            return;
        }

        env.log(Level.DEBUG, "Sending msg \"%s\"", message);

        final ByteBuf buf = channel.alloc().buffer()
                .writeByte(SEND_STRING_MSG)
                .writeBytes(message.getBytes());
        channel.writeAndFlush(buf);
    }

    private class ReceiverHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msgObj) {
            ByteBuf msg = (ByteBuf) msgObj;
            try {
                byte msgType = msg.readByte();
                switch (msgType) {

                    case RECV_CHUNK_DATA:
                        if (!msg.isReadable()) {
                            env.log(Level.WARN, "empty chunk received");
                            break;
                        }

                        SPacketChunkData chunkPacket = new SPacketChunkData();
                        // server needs to send this zero, because we cannot write to incoming `msg` buffer
//                        msg.writeByte(0); // number of tile entities in the chunk, not contained in our packet format
                        chunkPacket.readPacketData(new PacketBuffer(msg));
                        Pos2 pos = new Pos2(chunkPacket.getChunkX(), chunkPacket.getChunkZ());

                        moreChunks.onReceiveExtraChunk(new Chunk(pos, chunkPacket));
                        break;

                    case RECV_STATUS_MSG:
                        byte[] bb = new byte[msg.readableBytes()];
                        msg.readBytes(bb);
                        String statusMsg = new String(bb);

                        env.log(Level.DEBUG, "StatusMsg received: " + statusMsg);

                        moreChunks.onStatusMsg(statusMsg);
                        break;

                    default:
                        env.log(Level.ERROR, "Unexpected message type %d 0x%02x", msgType, msgType);
                }
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                msg.release();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            cause.printStackTrace();
            disconnect(new DisconnectReason("ChunkServer: Receiver Exception: " + cause.getMessage()));
        }
    }

    private LazyLoadBase<? extends EventLoopGroup> getLoopGroup() {
        if (Epoll.isAvailable() && Minecraft.getMinecraft().gameSettings.useNativeTransport) {
            return CLIENT_EPOLL_EVENTLOOP;
        } else {
            return CLIENT_NIO_EVENTLOOP;
        }
    }

    private Class<? extends SocketChannel> getChannelClass() {
        if (Epoll.isAvailable() && Minecraft.getMinecraft().gameSettings.useNativeTransport) {
            return EpollSocketChannel.class;
        } else {
            return NioSocketChannel.class;
        }
    }
}
