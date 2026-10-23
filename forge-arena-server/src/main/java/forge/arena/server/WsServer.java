package forge.arena.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolConfig;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GlobalEventExecutor;

import java.net.InetSocketAddress;
import java.util.List;

/** Dev per-seat WebSocket transport; seat tokens arrive in tasks 1.11/1.12. */
public final class WsServer implements AutoCloseable {
    static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();

    private final String host;
    private final int port;
    private final EventLoopGroup bossGroup = new NioEventLoopGroup(1);
    private final EventLoopGroup workerGroup = new NioEventLoopGroup();
    private final ChannelGroup channels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
    private final SeatWriter[] writers = new SeatWriter[4];
    private Channel serverChannel;

    public WsServer(final String host, final int port) {
        this.host = host;
        this.port = port;
        for (int seat = 0; seat < writers.length; seat++) {
            writers[seat] = new SeatWriter(seat);
        }
    }

    /** Bind on the lifecycle owner thread, never on a Netty I/O thread. */
    public void start() throws InterruptedException {
        checkLifecycleThread();
        if (serverChannel != null) {
            throw new IllegalStateException("WebSocket server already started");
        }
        final ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(final SocketChannel channel) {
                        channels.add(channel);
                        channel.pipeline().addLast(new HttpServerCodec(), new HttpObjectAggregator(65536),
                                // Netty 4.1.115 checkStartsWith accepts '?' after /ws.
                                new WebSocketServerProtocolHandler(WebSocketServerProtocolConfig.newBuilder()
                                        .websocketPath("/ws").checkStartsWith(true).build()), new MessageHandler());
                    }
                });
        serverChannel = bootstrap.bind(host, port).sync().channel();
        channels.add(serverChannel);
    }

    public int getPort() {
        if (serverChannel == null) {
            throw new IllegalStateException("WebSocket server has not started");
        }
        return ((InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    public SeatWriter writer(final int seat) {
        if (seat < 0 || seat >= writers.length) {
            throw new IllegalArgumentException("Seat must be 0..3");
        }
        return writers[seat];
    }

    /** Stop and await both groups on the lifecycle owner thread. */
    @Override
    public void close() {
        checkLifecycleThread();
        for (final SeatWriter writer : writers) {
            writer.close();
        }
        channels.close().syncUninterruptibly();
        final Future<?> bossStopped = bossGroup.shutdownGracefully();
        final Future<?> workerStopped = workerGroup.shutdownGracefully();
        bossStopped.syncUninterruptibly();
        workerStopped.syncUninterruptibly();
    }

    private void checkLifecycleThread() {
        for (final EventExecutor executor : bossGroup) {
            if (executor.inEventLoop()) {
                throw new IllegalStateException("WebSocket lifecycle must run outside Netty I/O threads");
            }
        }
        for (final EventExecutor executor : workerGroup) {
            if (executor.inEventLoop()) {
                throw new IllegalStateException("WebSocket lifecycle must run outside Netty I/O threads");
            }
        }
    }

    private final class MessageHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {
        private int seat = -1;
        @Override
        public void userEventTriggered(final ChannelHandlerContext ctx, final Object event) throws Exception {
            if (event instanceof WebSocketServerProtocolHandler.HandshakeComplete handshake) {
                try {
                    final QueryStringDecoder query = new QueryStringDecoder(handshake.requestUri());
                    final List<String> seats = query.parameters().get("seat");
                    if (!"/ws".equals(query.path()) || seats == null || seats.size() != 1
                            || !seats.get(0).matches("[0-3]")) {
                        throw new IllegalArgumentException("Connect to /ws?seat=N with exactly one seat from 0..3");
                    }
                    // No bridge lock or wait on I/O: the seat writer serializes hello, cached
                    // state, replacement and live sends, so handshake races cannot reorder them.
                    seat = Integer.parseInt(seats.get(0));
                    writer(seat).connect(ctx.channel());
                } catch (final IllegalArgumentException ex) {
                    ctx.writeAndFlush(new CloseWebSocketFrame(1008, "Invalid seat: use /ws?seat=N, N=0..3"))
                            .addListener(ChannelFutureListener.CLOSE);
                }
            }
            super.userEventTriggered(ctx, event);
        }

        @Override
        protected void channelRead0(final ChannelHandlerContext ctx, final TextWebSocketFrame frame) {
            System.out.println("[ws] in: " + frame.text());
            if (seat < 0) return;
            try {
                final JsonObject message = JsonParser.parseString(frame.text()).getAsJsonObject();
                if (message.has("type") && "promptReply".equals(message.get("type").getAsString())) {
                    writer(seat).promptReply(message);
                }
            } catch (RuntimeException ex) {
                final JsonObject rejected = new JsonObject();
                rejected.addProperty("type", "message"); rejected.addProperty("level", "rejected");
                rejected.addProperty("text", "Invalid JSON message"); writer(seat).sendFromIo(rejected);
            }
        }

        @Override
        public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) {
            System.err.println("[ws] " + cause);
            ctx.close();
        }
    }

}
